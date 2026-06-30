package com.choculaterie.gitlite.gui;

import com.choculaterie.gui.widget.SchematicRenderer;
import com.choculaterie.util.LitematicParser;
import com.choculaterie.gitlite.GitLite;
import com.choculaterie.gitlite.model.GitRepoDetailDto;
import com.choculaterie.gitlite.network.GitLiteNetworkManager;
import com.choculaterie.vanilib.gui.theme.UITheme;
import com.choculaterie.vanilib.gui.widget.CustomButton;
import com.choculaterie.vanilib.gui.widget.CustomTextField;
import com.choculaterie.vanilib.gui.widget.ToastManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.util.List;

/**
 * Previews a freshly-captured zone using LD's own {@link SchematicRenderer} - the exact same
 * 3D viewer LD shows when you double-click a file in its file manager - then pushes the
 * capture as a new commit via the existing {@link GitLiteNetworkManager#pushCommit} flow.
 */
public class ZoneCapturePreviewScreen extends Screen {

	private final Screen parent;
	private final GitRepoDetailDto repo;
	private final String branchName;
	private final File litematicFile;

	private final SchematicRenderer schematicRenderer = new SchematicRenderer();
	private volatile boolean isParsing = true;
	private volatile boolean parseFailed = false;

	private ToastManager toastManager;
	private CustomTextField messageField;
	private CustomButton pushButton;
	private boolean isPushing = false;

	public ZoneCapturePreviewScreen(Screen parent, GitRepoDetailDto repo, String branchName, File litematicFile) {
		super(Component.literal("Preview Capture"));
		this.parent = parent;
		this.repo = repo;
		this.branchName = branchName;
		this.litematicFile = litematicFile;
	}

	@Override
	protected void init() {
		super.init();
		toastManager = new ToastManager(this.minecraft);

		int previewHeight = previewHeight();

		new Thread(() -> {
			try {
				List<LitematicParser.BlockData> positions = LitematicParser.parseBlockPositions(litematicFile);
				schematicRenderer.setBlocks(positions);
				schematicRenderer.fitToPanel(this.width, previewHeight);
				isParsing = false;
			} catch (Exception e) {
				parseFailed = true;
				isParsing = false;
			}
		}, "GitLite-Capture-Preview").start();

		int fieldY = this.height - 60;
		messageField = new CustomTextField(this.minecraft, UITheme.Dimensions.PADDING, fieldY,
			this.width - UITheme.Dimensions.PADDING * 2, UITheme.Dimensions.BUTTON_HEIGHT, Component.literal("Message"));
		messageField.setPlaceholder(Component.literal("Commit message"));
		addRenderableWidget(messageField);

		int buttonY = this.height - 30;
		pushButton = new CustomButton(this.width / 2 - 85, buttonY, 80, UITheme.Dimensions.BUTTON_HEIGHT,
			Component.literal("Push"), b -> push());
		addRenderableWidget(pushButton);

		addRenderableWidget(new CustomButton(this.width / 2 + 5, buttonY, 80, UITheme.Dimensions.BUTTON_HEIGHT,
			Component.literal("Cancel"), b -> cancel()));
	}

	private int previewHeight() {
		return this.height - 100;
	}

	private void push() {
		if (isPushing) return;
		String message = messageField.getValue().trim();
		if (message.isEmpty()) {
			toastManager.showError("Commit message is required");
			return;
		}

		isPushing = true;
		toastManager.showInfo("Pushing capture...");

		GitLiteNetworkManager.pushCommit(repo.id, branchName, litematicFile, message).thenAccept(commit -> {
			if (this.minecraft != null) {
				this.minecraft.execute(() -> {
					toastManager.showSuccess("Pushed commit " + commit.id.substring(0, Math.min(8, commit.id.length())));
					this.minecraft.setScreen(parent);
				});
			}
		}).exceptionally(throwable -> {
			Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
			GitLite.LOGGER.error("[GitLite] Push failed", cause);
			if (this.minecraft != null) {
				this.minecraft.execute(() -> {
					isPushing = false;
					toastManager.showError("Push failed: " + cause.getMessage());
				});
			}
			return null;
		});
	}

	private void cancel() {
		if (this.minecraft != null) this.minecraft.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, UITheme.Colors.PANEL_BG);

		int previewHeight = previewHeight();
		if (isParsing || schematicRenderer.isBuilding()) {
			context.centeredText(font, "Loading 3D preview...", this.width / 2, previewHeight / 2, 0xFFFFAA00);
		} else if (parseFailed) {
			context.centeredText(font, "Failed to load 3D preview", this.width / 2, previewHeight / 2, 0xFFFF4444);
		} else if (schematicRenderer.isEmpty()) {
			context.centeredText(font, "No blocks found", this.width / 2, previewHeight / 2, 0xFF888888);
		} else {
			schematicRenderer.render(context, 0, 0, this.width, previewHeight, mouseX, mouseY);
		}

		super.extractRenderState(context, mouseX, mouseY, delta);
		if (toastManager != null) {
			toastManager.render(context, delta, mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (super.mouseDragged(event, dragX, dragY)) return true;
		schematicRenderer.onDrag(dragX, dragY, event.button());
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		schematicRenderer.onScroll(verticalAmount);
		return true;
	}

	@Override
	public void onClose() {
		cancel();
	}

	@Override
	public void removed() {
		schematicRenderer.close();
	}
}

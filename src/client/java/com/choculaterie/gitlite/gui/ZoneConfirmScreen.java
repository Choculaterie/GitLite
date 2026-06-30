package com.choculaterie.gitlite.gui;

import com.choculaterie.gitlite.GitLite;
import com.choculaterie.gitlite.model.GitRepoDetailDto;
import com.choculaterie.gitlite.network.GitLiteNetworkManager;
import com.choculaterie.gitlite.selection.ZoneSelectionManager;
import com.choculaterie.vanilib.gui.theme.UITheme;
import com.choculaterie.vanilib.gui.widget.ConfirmPopup;
import com.choculaterie.vanilib.gui.widget.CustomButton;
import com.choculaterie.vanilib.gui.widget.ToastManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Shown once both zone positions are set: displays the selection's dimensions and a Save action
 * that persists the zone to this repository, locally and in the cloud - like a Litematica
 * placement, it stays saved (and its live overlay box keeps showing) until redefined. Capturing
 * and pushing happen later, from the repo's existing Push action, which already knows the zone
 * once one is saved.
 */
public class ZoneConfirmScreen extends Screen {

	private final Screen parent;
	private final GitRepoDetailDto repo;

	private ToastManager toastManager;
	private ConfirmPopup confirmPopup;

	public ZoneConfirmScreen(Screen parent, GitRepoDetailDto repo) {
		super(Component.literal("Confirm Zone"));
		this.parent = parent;
		this.repo = repo;
	}

	@Override
	protected void init() {
		super.init();
		toastManager = new ToastManager(this.minecraft);

		int cx = this.width / 2;
		int rowY = this.height / 2 + 10;

		addRenderableWidget(new CustomButton(cx - 85, rowY, 80, UITheme.Dimensions.BUTTON_HEIGHT,
			Component.literal("Save Zone"), b -> openConfirmPopup()));

		addRenderableWidget(new CustomButton(cx + 5, rowY, 80, UITheme.Dimensions.BUTTON_HEIGHT,
			Component.literal("Cancel"), b -> cancel()));
	}

	private void openConfirmPopup() {
		confirmPopup = new ConfirmPopup(this, "Save Zone",
			"Save this zone to the repository? It stays linked here, and pushing will re-capture its current contents as a new commit.",
			this::doSave, () -> confirmPopup = null, "Save");
	}

	private void doSave() {
		confirmPopup = null;
		BlockPos pos1 = ZoneSelectionManager.getPos1();
		BlockPos pos2 = ZoneSelectionManager.getPos2();
		if (pos1 == null || pos2 == null) return;

		ZoneSelectionManager.saveZone(repo.id, pos1, pos2);
		ZoneSelectionManager.disarm();
		toastManager.showSuccess("Zone saved to " + repo.name);

		GitLiteNetworkManager.saveZone(repo.id, pos1.getX(), pos1.getY(), pos1.getZ(), pos2.getX(), pos2.getY(), pos2.getZ())
			.exceptionally(throwable -> {
				GitLite.LOGGER.error("[GitLite] Failed to sync zone to cloud", throwable);
				if (this.minecraft != null) {
					this.minecraft.execute(() -> toastManager.showError("Zone saved locally, but cloud sync failed: "
						+ (throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage())));
				}
				return null;
			});

		if (this.minecraft != null) this.minecraft.setScreen(parent);
	}

	private void cancel() {
		ZoneSelectionManager.disarm();
		if (this.minecraft != null) this.minecraft.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, UITheme.Colors.PANEL_BG);
		super.extractRenderState(context, mouseX, mouseY, delta);

		int cx = this.width / 2;
		int titleY = this.height / 2 - 30;
		context.centeredText(font, "Zone Selected", cx, titleY, UITheme.Colors.TEXT_PRIMARY);

		String dims = ZoneSelectionManager.getSizeX() + " x " + ZoneSelectionManager.getSizeY() + " x " + ZoneSelectionManager.getSizeZ();
		context.centeredText(font, dims, cx, titleY + UITheme.Typography.LINE_HEIGHT, UITheme.Colors.TEXT_SUBTITLE);

		if (confirmPopup != null) {
			confirmPopup.extractRenderState(context, mouseX, mouseY, delta);
		}
		if (toastManager != null) {
			toastManager.render(context, delta, mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (confirmPopup != null) {
			return confirmPopup.mouseClicked(click.x(), click.y(), click.button());
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (confirmPopup != null) {
			return confirmPopup.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void onClose() {
		cancel();
	}
}

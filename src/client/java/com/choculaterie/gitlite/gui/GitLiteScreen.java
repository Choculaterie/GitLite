package com.choculaterie.gitlite.gui;

import com.choculaterie.gitlite.GitLite;
import com.choculaterie.gitlite.config.GitLiteSettings;
import com.choculaterie.gitlite.litematic.LitematicReader;
import com.choculaterie.gitlite.litematic.LitematicWriter;
import com.choculaterie.gitlite.selection.ZoneSelectionManager;
import com.choculaterie.gui.widget.SchematicRenderer;
import com.choculaterie.util.LitematicParser;
import com.choculaterie.vanilib.gui.theme.UITheme;
import com.choculaterie.vanilib.gui.widget.ConfirmPopup;
import com.choculaterie.vanilib.gui.widget.CustomButton;
import com.choculaterie.vanilib.gui.widget.CustomTextField;
import com.choculaterie.vanilib.gui.widget.ToastManager;
import com.choculaterie.gitlite.gui.GitLiteAccountLinkingScreen;
import com.choculaterie.gitlite.model.GitBranchDto;
import com.choculaterie.gitlite.model.GitCommitDto;
import com.choculaterie.gitlite.model.GitRepoDetailDto;
import com.choculaterie.gitlite.model.GitRepoDto;
import com.choculaterie.gitlite.network.GitLiteNetworkManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The main GitLite screen, presenting a two-panel layout:
 * <ul>
 *   <li><b>Left panel</b> - paginated list of the user's repositories with New/Refresh
 *       actions.</li>
 *   <li><b>Right panel</b> - a live 3D preview of the repo's saved zone, commit history,
 *       branch selector, and Release/Push actions for the currently selected repository.</li>
 * </ul>
 *
 * <p>If no API key is configured in {@link GitLiteSettings}, the screen immediately
 * redirects to {@link GitLiteAccountLinkingScreen} so the user can link their
 * Choculaterie account first.
 *
 * <p>Destructive or network-heavy actions (create repo, push, release) open a centred
 * modal dialog collected in the {@link Modal} enum. Modals are rendered on top of
 * the main layout and block all other input while open.
 *
 * <p>All network calls are dispatched asynchronously and marshalled back to the
 * game thread via {@code minecraft.execute()} before touching UI state.
 */
public class GitLiteScreen extends Screen {

	private static final int PADDING      = UITheme.Dimensions.PADDING;
	private static final int BUTTON_HEIGHT = UITheme.Dimensions.BUTTON_HEIGHT;
	private static final int ROW_HEIGHT   = 22; // pixel height of each repository row button
	private static final int REPOS_PER_PAGE = 8;
	private static final int MODAL_WIDTH  = 280;
	private static final int MODAL_HEIGHT = 130;

	/** Identifies which modal dialog is currently open. */
	private enum Modal {
		NONE,
		NEW_REPO,
		PUSH,
		RELEASE
	}

	private final Screen parent;
	private ToastManager toastManager;

	// --- toolbar buttons ---
	private CustomButton backButton;
	private CustomButton settingsButton;
	private CustomButton newRepoButton;
	private CustomButton refreshButton;

	// --- repo list pagination ---
	private CustomButton prevRepoPageButton;
	private CustomButton nextRepoPageButton;

	// --- right-panel actions ---
	private CustomButton pushButton;
	private CustomButton releaseButton;
	private CustomButton captureZoneButton;
	private CustomButton prevCommitPageButton;
	private CustomButton nextCommitPageButton;

	// --- live zone preview, always visible once a repo with a saved zone is selected ---
	private static final int PREVIEW_HEIGHT = 130;
	private enum PreviewStatus { NO_WORLD, NO_ZONE, LOADING, READY, EMPTY, TOO_LARGE }
	private SchematicRenderer zonePreviewRenderer;
	private volatile PreviewStatus previewStatus = PreviewStatus.NO_ZONE;

	/** Dynamically rebuilt each page; one button per visible repo row. */
	private final List<CustomButton> repoRowButtons = new ArrayList<>();
	private final List<GitRepoDto> repos = new ArrayList<>();
	private int repoPage = 1;
	private boolean loadingRepos = false;

	private GitRepoDetailDto selectedRepo;
	private String selectedBranchName;
	private CustomButton prevBranchButton;
	private CustomButton nextBranchButton;
	private CustomButton modulesButton;
	private final List<GitCommitDto> commits = new ArrayList<>();
	private int commitPage = 1;
	private int commitTotalPages = 1;
	private boolean loadingCommits = false;
	/** Clickable hit-region per visible commit row (parallel to {@link #commits}), recomputed each render. */
	private final List<int[]> commitRowBounds = new ArrayList<>();
	private ConfirmPopup revertConfirmPopup;

	// --- modal state ---
	private Modal modal = Modal.NONE;
	private CustomTextField modalField1;
	private CustomTextField modalField2;
	private CustomButton modalConfirmButton;
	private CustomButton modalCancelButton;

	// -------------------------------------------------------------------------
	// Constructor
	// -------------------------------------------------------------------------

	/**
	 * Creates a new {@code GitLiteScreen}.
	 *
	 * @param parent the screen to return to when this screen is closed
	 */
	public GitLiteScreen(Screen parent) {
		super(Component.literal("GitLite"));
		this.parent = parent;
	}

	// -------------------------------------------------------------------------
	// Screen lifecycle
	// -------------------------------------------------------------------------

	@Override
	protected void init() {
		super.init();

		if (this.toastManager == null) {
			this.toastManager = new ToastManager(this.minecraft);
		}

		// Redirect unauthenticated users to the account-linking flow.
		if (!GitLiteSettings.getInstance().hasApiKey()) {
			if (this.minecraft != null) {
				this.minecraft.setScreen(new GitLiteAccountLinkingScreen(this.parent));
			}
			return;
		}

		if (zonePreviewRenderer != null) {
			zonePreviewRenderer.close();
		}
		zonePreviewRenderer = new SchematicRenderer();

		initMainLayout();
		if (repos.isEmpty() && !loadingRepos) {
			loadRepos();
		}
		// Re-init runs every time this screen regains focus (e.g. returning from a push/revert/
		// release sub-screen), so refreshing here keeps the commit list and zone preview current
		// without the user needing to reselect the repo.
		if (selectedRepo != null) {
			loadCommits();
		}
		refreshZonePreview();
	}

	@Override
	public void removed() {
		if (zonePreviewRenderer != null) {
			zonePreviewRenderer.close();
			zonePreviewRenderer = null;
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, UITheme.Colors.PANEL_BG);

		if (!GitLiteSettings.getInstance().hasApiKey()) {
			return;
		}
		renderMainLayout(context, mouseX, mouseY, delta);
		if (modal != Modal.NONE) {
			renderModal(context, mouseX, mouseY, delta);
		}
		if (revertConfirmPopup != null) {
			revertConfirmPopup.extractRenderState(context, mouseX, mouseY, delta);
		}

		if (toastManager != null) {
			toastManager.render(context, delta, mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		double mouseX = click.x();
		double mouseY = click.y();

		if (toastManager != null && toastManager.mouseClicked(mouseX, mouseY)) {
			return true;
		}

		if (!GitLiteSettings.getInstance().hasApiKey()) {
			return super.mouseClicked(click, doubled);
		}

		if (revertConfirmPopup != null) {
			return revertConfirmPopup.mouseClicked(mouseX, mouseY, click.button());
		}

		// While a modal is open, only its own widgets should receive clicks.
		if (modal != Modal.NONE) {
			if (modalField1 != null) {
				modalField1.setFocused(modalField1.isMouseOver(mouseX, mouseY));
			}
			if (modalField2 != null) {
				modalField2.setFocused(modalField2.isMouseOver(mouseX, mouseY));
			}
			if (modalConfirmButton != null && modalConfirmButton.mouseClicked(click, doubled)) {
				return true;
			}
			if (modalCancelButton != null && modalCancelButton.mouseClicked(click, doubled)) {
				return true;
			}
			return true; // swallow clicks outside modal widgets
		}

		if (backButton.mouseClicked(click, doubled)) return true;
		if (settingsButton.mouseClicked(click, doubled)) return true;
		if (refreshButton.mouseClicked(click, doubled)) return true;
		if (newRepoButton.mouseClicked(click, doubled)) return true;

		for (CustomButton row : repoRowButtons) {
			if (row.mouseClicked(click, doubled)) return true;
		}

		if (prevRepoPageButton.mouseClicked(click, doubled)) return true;
		if (nextRepoPageButton.mouseClicked(click, doubled)) return true;

		if (selectedRepo != null) {
			if (captureZoneButton.mouseClicked(click, doubled)) return true;
			if (releaseButton.mouseClicked(click, doubled)) return true;
			if (pushButton.mouseClicked(click, doubled)) return true;
			if (prevCommitPageButton.mouseClicked(click, doubled)) return true;
			if (nextCommitPageButton.mouseClicked(click, doubled)) return true;
			if (prevBranchButton.visible && prevBranchButton.mouseClicked(click, doubled)) return true;
			if (nextBranchButton.visible && nextBranchButton.mouseClicked(click, doubled)) return true;
			if (modulesButton.visible && modulesButton.mouseClicked(click, doubled)) return true;

			for (int i = 0; i < commitRowBounds.size() && i < commits.size(); i++) {
				int[] b = commitRowBounds.get(i);
				if (mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3]) {
					confirmRevertCommit(commits.get(i));
					return true;
				}
			}
		}

		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (super.mouseDragged(event, dragX, dragY)) return true;
		if (modal == Modal.NONE && selectedRepo != null && previewStatus == PreviewStatus.READY) {
			zonePreviewRenderer.onDrag(dragX, dragY, event.button());
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (revertConfirmPopup != null) {
			return revertConfirmPopup.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		}
		if (modal == Modal.NONE && selectedRepo != null && previewStatus == PreviewStatus.READY) {
			zonePreviewRenderer.onScroll(verticalAmount);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void onClose() {
		CustomTextField.restoreMinecraftCharCallback();
		if (this.minecraft != null) {
			this.minecraft.setScreen(parent);
		}
	}

	// -------------------------------------------------------------------------
	// Layout initialisation
	// -------------------------------------------------------------------------

	private void initMainLayout() {
		int leftPanelWidth = getLeftPanelWidth();
		int rightPanelX   = getRightPanelX();
		int paginationY   = this.height - PADDING - BUTTON_HEIGHT;

		backButton     = new CustomButton(PADDING, PADDING, BUTTON_HEIGHT, BUTTON_HEIGHT, Component.literal("←"), button -> this.onClose());
		settingsButton = new CustomButton(PADDING + BUTTON_HEIGHT + 4, PADDING, BUTTON_HEIGHT, BUTTON_HEIGHT, Component.literal("⚙"), button -> this.minecraft.setScreen(new GitLiteAccountLinkingScreen(this)));
		refreshButton  = new CustomButton(leftPanelWidth - PADDING - BUTTON_HEIGHT - 90, PADDING, BUTTON_HEIGHT, BUTTON_HEIGHT, Component.literal("⟳"), button -> loadRepos());
		newRepoButton  = new CustomButton(leftPanelWidth - PADDING - 80, PADDING, 80, BUTTON_HEIGHT, Component.literal("+ New Repo"), button -> openModal(Modal.NEW_REPO));

		prevRepoPageButton = new CustomButton(PADDING, paginationY, 60, BUTTON_HEIGHT, Component.literal("< Prev"), button -> changeRepoPage(-1));
		nextRepoPageButton = new CustomButton(PADDING + 65, paginationY, 60, BUTTON_HEIGHT, Component.literal("Next >"), button -> changeRepoPage(1));

		captureZoneButton = new CustomButton(this.width - PADDING - 250, PADDING, 100, BUTTON_HEIGHT, Component.literal("Set Zone"), button -> {
			if (selectedRepo == null) {
				toastManager.showError("Select a repository first");
			} else {
				armZoneCapture();
			}
		});
		releaseButton = new CustomButton(this.width - PADDING - 145, PADDING, 70, BUTTON_HEIGHT, Component.literal("Release"), button -> {
			if (selectedRepo == null) {
				toastManager.showError("Select a repository first");
			} else {
				openModal(Modal.RELEASE);
			}
		});
		pushButton = new CustomButton(this.width - PADDING - 75, PADDING, 75, BUTTON_HEIGHT, Component.literal("Push"), button -> {
			if (selectedRepo == null) {
				toastManager.showError("Select a repository first");
			} else if (ZoneSelectionManager.hasSavedZone(selectedRepo.id)) {
				pushFromSavedZone();
			} else {
				openModal(Modal.PUSH);
			}
		});

		int rightPanelWidth = this.width - getRightPanelX() - PADDING;
		int branchY = this.height / 2 - BUTTON_HEIGHT / 2;
		prevBranchButton = new CustomButton(getRightPanelX(), branchY, BUTTON_HEIGHT, BUTTON_HEIGHT, Component.literal("<"), button -> cycleBranch(-1));
		nextBranchButton = new CustomButton(getRightPanelX() + rightPanelWidth - BUTTON_HEIGHT, branchY, BUTTON_HEIGHT, BUTTON_HEIGHT, Component.literal(">"), button -> cycleBranch(1));
		modulesButton    = new CustomButton(this.width - PADDING - 85, paginationY, 85, BUTTON_HEIGHT, Component.literal("Modules"), button -> openModuleBrowser());

		prevBranchButton.visible = false;
		nextBranchButton.visible = false;
		modulesButton.visible    = false;

		prevCommitPageButton = new CustomButton(rightPanelX, paginationY, 60, BUTTON_HEIGHT, Component.literal("< Prev"), button -> changeCommitPage(-1));
		nextCommitPageButton = new CustomButton(rightPanelX + 65, paginationY, 60, BUTTON_HEIGHT, Component.literal("Next >"), button -> changeCommitPage(1));

		rebuildRepoRows();
		updateRepoPaginationButtons();
		updateCommitPaginationButtons();
	}

	// -------------------------------------------------------------------------
	// Panel geometry helpers
	// -------------------------------------------------------------------------

	private int getLeftPanelWidth() {
		return this.width / 2;
	}

	private int getRightPanelX() {
		return getLeftPanelWidth() + PADDING;
	}

	private int getRightPanelWidth() {
		return this.width - getRightPanelX() - PADDING;
	}

	// -------------------------------------------------------------------------
	// Repo list management
	// -------------------------------------------------------------------------

	/** Rebuilds the repo row buttons for the current page from the cached {@link #repos} list. */
	private void rebuildRepoRows() {
		repoRowButtons.clear();

		int rowWidth   = getLeftPanelWidth() - PADDING * 2;
		int startIndex = (repoPage - 1) * REPOS_PER_PAGE;
		int endIndex   = Math.min(startIndex + REPOS_PER_PAGE, repos.size());
		int y = PADDING * 2 + BUTTON_HEIGHT + PADDING;

		for (int i = startIndex; i < endIndex; i++) {
			GitRepoDto repo = repos.get(i);
			String label = truncate(repo.name + "  (" + repo.defaultBranchName + ")", rowWidth - PADDING * 2);
			CustomButton button = new CustomButton(PADDING, y, rowWidth, ROW_HEIGHT, Component.literal(label), btn -> selectRepo(repo));
			repoRowButtons.add(button);
			y += ROW_HEIGHT + 2;
		}
	}

	private int getTotalRepoPages() {
		return Math.max(1, (int) Math.ceil(repos.size() / (double) REPOS_PER_PAGE));
	}

	private void changeRepoPage(int delta) {
		int newPage = repoPage + delta;
		if (newPage < 1 || newPage > getTotalRepoPages()) {
			return;
		}
		repoPage = newPage;
		rebuildRepoRows();
		updateRepoPaginationButtons();
	}

	private void updateRepoPaginationButtons() {
		int totalPages = getTotalRepoPages();
		prevRepoPageButton.active = repoPage > 1;
		nextRepoPageButton.active = repoPage < totalPages;
	}

	// -------------------------------------------------------------------------
	// Commit list management
	// -------------------------------------------------------------------------

	private void changeCommitPage(int delta) {
		int newPage = commitPage + delta;
		if (newPage < 1 || newPage > commitTotalPages) {
			return;
		}
		commitPage = newPage;
		loadCommits();
	}

	private void updateCommitPaginationButtons() {
		prevCommitPageButton.active = commitPage > 1;
		nextCommitPageButton.active = commitPage < commitTotalPages;
	}

	// -------------------------------------------------------------------------
	// Network operations
	// -------------------------------------------------------------------------

	private void loadRepos() {
		loadingRepos = true;
		GitLiteNetworkManager.listRepos().thenAccept(result -> {
			if (this.minecraft != null) {
				this.minecraft.execute(() -> {
					loadingRepos = false;
					repos.clear();
					if (result != null) {
						repos.addAll(result);
					}
					repoPage = 1;
					rebuildRepoRows();
					updateRepoPaginationButtons();
				});
			}
		}).exceptionally(throwable -> {
			if (this.minecraft != null) {
				this.minecraft.execute(() -> {
					loadingRepos = false;
					showNetworkError("Failed to load repositories", throwable);
				});
			}
			return null;
		});
	}

	/** Fetches the full detail for {@code repo} and selects it in the right panel. */
	private void selectRepo(GitRepoDto repo) {
		GitLiteNetworkManager.getRepo(repo.id).thenAccept(detail -> {
			if (this.minecraft != null) {
				this.minecraft.execute(() -> {
					selectedRepo = detail;
					selectedBranchName = detail.defaultBranchName;
					if (detail.zone != null) {
						ZoneSelectionManager.applyCloudZone(detail.id, detail.zone.x1, detail.zone.y1, detail.zone.z1,
							detail.zone.x2, detail.zone.y2, detail.zone.z2);
					}
					ZoneSelectionManager.setActiveRepo(detail.id);
					commitPage = 1;
					boolean hasBranches = detail.branches != null && detail.branches.size() > 1;
					if (prevBranchButton != null) prevBranchButton.visible = hasBranches;
					if (nextBranchButton != null) nextBranchButton.visible = hasBranches;
					if (modulesButton != null) modulesButton.visible = true;
					loadCommits();
					refreshZonePreview();
				});
			}
		}).exceptionally(throwable -> {
			if (this.minecraft != null) {
				this.minecraft.execute(() -> showNetworkError("Failed to load repository", throwable));
			}
			return null;
		});
	}

	/**
	 * Cycles the selected branch by {@code direction} steps (wrapping around) and
	 * refreshes the commit history.
	 *
	 * @param direction +1 to advance, -1 to go back
	 */
	private void cycleBranch(int direction) {
		if (selectedRepo == null || selectedRepo.branches == null || selectedRepo.branches.isEmpty()) return;
		int idx = 0;
		for (int i = 0; i < selectedRepo.branches.size(); i++) {
			if (selectedRepo.branches.get(i).name.equals(selectedBranchName)) {
				idx = i;
				break;
			}
		}
		idx = (idx + direction + selectedRepo.branches.size()) % selectedRepo.branches.size();
		selectedBranchName = selectedRepo.branches.get(idx).name;
		commitPage = 1;
		loadCommits();
	}

	private void openModuleBrowser() {
		if (selectedRepo == null || selectedBranchName == null) {
			toastManager.showError("Select a repository first");
			return;
		}
		if (this.minecraft != null) {
			this.minecraft.setScreen(new com.choculaterie.gitlite.gui.ModuleBrowserScreen(this, selectedRepo, selectedBranchName));
		}
	}

	private void loadCommits() {
		if (selectedRepo == null) {
			return;
		}
		loadingCommits = true;
		String branch = selectedBranchName != null ? selectedBranchName : selectedRepo.defaultBranchName;
		GitLiteNetworkManager.listCommits(selectedRepo.id, branch, commitPage, 10).thenAccept(result -> {
			if (this.minecraft != null) {
				this.minecraft.execute(() -> {
					loadingCommits = false;
					commits.clear();
					if (result != null && result.commits != null) {
						commits.addAll(result.commits);
						commitTotalPages = Math.max(1, result.totalPages);
					} else {
						commitTotalPages = 1;
					}
					updateCommitPaginationButtons();
				});
			}
		}).exceptionally(throwable -> {
			if (this.minecraft != null) {
				this.minecraft.execute(() -> {
					loadingCommits = false;
					showNetworkError("Failed to load commits", throwable);
				});
			}
			return null;
		});
	}

	private void createRepo(String name, String description) {
		GitLiteNetworkManager.createRepo(name, description).thenAccept(repo -> {
			if (this.minecraft != null) {
				this.minecraft.execute(() -> {
					toastManager.showSuccess("Repository created: " + repo.name);
					repos.add(0, repo);
					repoPage = 1;
					rebuildRepoRows();
					updateRepoPaginationButtons();
					selectedRepo = repo;
					selectedBranchName = repo.defaultBranchName;
					ZoneSelectionManager.setActiveRepo(repo.id);
					if (modulesButton != null) modulesButton.visible = true;
					commitPage = 1;
					loadCommits();
					refreshZonePreview();
				});
			}
		}).exceptionally(throwable -> {
			if (this.minecraft != null) {
				this.minecraft.execute(() -> showNetworkError("Failed to create repository", throwable));
			}
			return null;
		});
	}

	private void pushCommitFile(File file, String message) {
		if (selectedRepo == null) {
			return;
		}
		toastManager.showInfo("Pushing " + file.getName() + "...");
		String branch = selectedBranchName != null ? selectedBranchName : selectedRepo.defaultBranchName;
		GitLiteNetworkManager.pushCommit(selectedRepo.id, branch, file, message).thenAccept(commit -> {
			if (this.minecraft != null) {
				this.minecraft.execute(() -> {
					toastManager.showSuccess("Pushed commit " + shortId(commit.id));
					commitPage = 1;
					loadCommits();
				});
			}
		}).exceptionally(throwable -> {
			if (this.minecraft != null) {
				this.minecraft.execute(() -> showNetworkError("Push failed", throwable));
			}
			return null;
		});
	}

	/** Arms the in-world zone-selection tool and closes this screen so the player can click blocks. */
	private void armZoneCapture() {
		if (this.minecraft == null) return;
		GitRepoDetailDto repo = selectedRepo;

		ZoneSelectionManager.arm(() -> {
			if (this.minecraft != null) {
				this.minecraft.setScreen(new ZoneConfirmScreen(this, repo));
			}
		});
		this.minecraft.setScreen(null);
		if (this.minecraft.player != null) {
			this.minecraft.player.sendSystemMessage(Component.literal(
				"GitLite zone capture armed. Left-click a block for Position 1, right-click for Position 2."));
		}
	}

	/**
	 * Captures the repo's saved zone's current contents and opens the push/preview screen
	 * directly. The world read happens synchronously here on the main thread - {@code Level}
	 * access from a background thread is not safe - and only the resulting in-memory snapshot is
	 * handed off to a background thread for the slower NBT/file-IO work.
	 */
	private void pushFromSavedZone() {
		if (this.minecraft == null || this.minecraft.level == null || selectedRepo == null) return;

		ZoneSelectionManager.SavedZone zone = ZoneSelectionManager.getSavedZone(selectedRepo.id);
		if (zone == null) return;

		String branch = selectedBranchName != null ? selectedBranchName : selectedRepo.defaultBranchName;
		GitRepoDetailDto repo = selectedRepo;
		String author = this.minecraft.getUser() != null ? this.minecraft.getUser().getName() : "Unknown";

		LitematicWriter.CaptureSnapshot snapshot;
		try {
			snapshot = LitematicWriter.readSnapshot(this.minecraft.level, zone.pos1(), zone.pos2());
		} catch (IllegalArgumentException e) {
			toastManager.showError(e.getMessage());
			return;
		}

		new Thread(() -> {
			try {
				File file = LitematicWriter.writeToFile(snapshot, repo.name, author, "");
				if (this.minecraft != null) {
					this.minecraft.execute(() -> this.minecraft.setScreen(new ZoneCapturePreviewScreen(this, repo, branch, file)));
				}
			} catch (Exception e) {
				GitLite.LOGGER.error("[GitLite] Zone capture failed", e);
				if (this.minecraft != null) {
					this.minecraft.execute(() -> toastManager.showError("Capture failed: " + e.getMessage()));
				}
			}
		}, "GitLite-Zone-Capture").start();
	}

	/**
	 * Confirms before reverting the zone to a past commit's contents - this overwrites whatever
	 * is currently in the zone, locally, without pushing anything.
	 */
	private void confirmRevertCommit(GitCommitDto commit) {
		if (selectedRepo == null) return;
		if (!ZoneSelectionManager.hasSavedZone(selectedRepo.id)) {
			toastManager.showError("No zone set for this repository");
			return;
		}
		if (this.minecraft == null || this.minecraft.level == null) {
			toastManager.showError("Open this in-world to revert a zone");
			return;
		}

		revertConfirmPopup = new ConfirmPopup(this, "Revert to Commit",
			"Revert the zone to \"" + commit.message + "\"? This overwrites the zone's current contents in your world and is not pushed anywhere.",
			() -> performRevert(commit), () -> revertConfirmPopup = null, "Revert");
	}

	/**
	 * Downloads a commit's litematic and pastes it directly into the repo's saved zone. Block
	 * reads/writes that touch the world must stay on the main thread; only the download and NBT
	 * parsing are offloaded to a background thread.
	 */
	private void performRevert(GitCommitDto commit) {
		revertConfirmPopup = null;
		if (selectedRepo == null || this.minecraft == null || this.minecraft.level == null) return;

		ZoneSelectionManager.SavedZone zone = ZoneSelectionManager.getSavedZone(selectedRepo.id);
		if (zone == null) return;

		BlockPos origin = new BlockPos(
			Math.min(zone.x1(), zone.x2()), Math.min(zone.y1(), zone.y2()), Math.min(zone.z1(), zone.z2()));

		toastManager.showInfo("Reverting zone...");
		GitLiteNetworkManager.downloadCommit(commit.id).thenAccept(bytes -> {
			LitematicWriter.CaptureSnapshot snapshot;
			try {
				snapshot = LitematicReader.readFromBytes(bytes);
			} catch (Exception e) {
				GitLite.LOGGER.error("[GitLite] Revert parse failed", e);
				if (this.minecraft != null) {
					this.minecraft.execute(() -> toastManager.showError("Revert failed: " + e.getMessage()));
				}
				return;
			}

			if (this.minecraft == null) return;
			this.minecraft.execute(() -> {
				MinecraftServer server = this.minecraft.getSingleplayerServer();
				if (server == null) {
					toastManager.showError("Revert only works in singleplayer worlds you're hosting");
					return;
				}
				ServerLevel serverLevel = server.getLevel(this.minecraft.level.dimension());
				if (serverLevel == null) {
					toastManager.showError("Could not find a matching server level to revert");
					return;
				}
				server.execute(() -> {
					LitematicReader.paste(serverLevel, snapshot, origin);
					// The client's own world view only updates once the resulting block-change
					// packets round-trip back to it, which doesn't happen synchronously with the
					// server-side paste above - give that a moment before refreshing the preview.
					new Thread(() -> {
						try {
							Thread.sleep(200);
						} catch (InterruptedException ignored) {
							return;
						}
						if (this.minecraft != null) {
							this.minecraft.execute(this::refreshZonePreview);
						}
					}, "GitLite-Revert-Refresh").start();
				});
				toastManager.showSuccess("Reverted to commit " + shortId(commit.id));
			});
		}).exceptionally(throwable -> {
			Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
			GitLite.LOGGER.error("[GitLite] Revert download failed", cause);
			if (this.minecraft != null) {
				this.minecraft.execute(() -> toastManager.showError("Revert failed: " + cause.getMessage()));
			}
			return null;
		});
	}

	/**
	 * (Re)builds the live zone preview from the world's current state - the same 3D viewer LD
	 * shows when you double-click a file in its file manager. Safe to call any time; no-ops if
	 * there's no world, no selected repo, or no saved zone to preview.
	 */
	private void refreshZonePreview() {
		if (this.minecraft == null || zonePreviewRenderer == null) return;

		if (this.minecraft.level == null) {
			previewStatus = PreviewStatus.NO_WORLD;
			return;
		}
		if (selectedRepo == null) {
			previewStatus = PreviewStatus.NO_ZONE;
			return;
		}
		ZoneSelectionManager.SavedZone zone = ZoneSelectionManager.getSavedZone(selectedRepo.id);
		if (zone == null) {
			previewStatus = PreviewStatus.NO_ZONE;
			return;
		}

		previewStatus = PreviewStatus.LOADING;
		LitematicWriter.CaptureSnapshot snapshot;
		try {
			snapshot = LitematicWriter.readSnapshot(this.minecraft.level, zone.pos1(), zone.pos2());
		} catch (IllegalArgumentException e) {
			previewStatus = PreviewStatus.TOO_LARGE;
			return;
		}

		int previewWidth = getRightPanelWidth();
		new Thread(() -> {
			List<LitematicParser.BlockData> blocks = LitematicWriter.toBlockDataList(snapshot);
			if (this.minecraft != null) {
				this.minecraft.execute(() -> {
					if (zonePreviewRenderer == null) return;
					zonePreviewRenderer.setBlocks(blocks);
					zonePreviewRenderer.fitToPanel(previewWidth, PREVIEW_HEIGHT);
					previewStatus = blocks.isEmpty() ? PreviewStatus.EMPTY : PreviewStatus.READY;
				});
			}
		}, "GitLite-Zone-Preview").start();
	}

	private void releaseCommit(String commitId, String tagName, File thumbnailFile) {
		if (selectedRepo == null) {
			return;
		}
		toastManager.showInfo("Publishing release " + tagName + "...");
		GitLiteNetworkManager.createRelease(selectedRepo.id, commitId, tagName, null, null, thumbnailFile).thenAccept(release -> {
			if (this.minecraft != null) {
				this.minecraft.execute(() -> toastManager.showSuccess("Published release " + release.tagName));
			}
		}).exceptionally(throwable -> {
			if (this.minecraft != null) {
				this.minecraft.execute(() -> showNetworkError("Release failed", throwable));
			}
			return null;
		});
	}

	/** Logs and displays the human-readable cause of a network failure as an error toast. */
	private void showNetworkError(String prefix, Throwable throwable) {
		Throwable cause   = throwable.getCause() != null ? throwable.getCause() : throwable;
		String message    = cause.getMessage() != null ? cause.getMessage() : "Unknown error";
		GitLite.LOGGER.error("[GitLite] {}: {}", prefix, message, cause);
		toastManager.showError(prefix + ": " + message);
	}

	// -------------------------------------------------------------------------
	// Modal management
	// -------------------------------------------------------------------------

	/** Opens the specified modal and populates its text fields with appropriate defaults. */
	private void openModal(Modal type) {
		if (this.minecraft == null) {
			return;
		}
		this.modal = type;

		int boxX       = (this.width - MODAL_WIDTH) / 2;
		int boxY       = (this.height - MODAL_HEIGHT) / 2;
		int fieldWidth = MODAL_WIDTH - PADDING * 2;
		int fieldX     = boxX + PADDING;
		int field1Y    = boxY + 30;
		int field2Y    = field1Y + BUTTON_HEIGHT + PADDING;

		modalField1 = new CustomTextField(this.minecraft, fieldX, field1Y, fieldWidth, BUTTON_HEIGHT, Component.literal("Field1"));
		modalField2 = new CustomTextField(this.minecraft, fieldX, field2Y, fieldWidth, BUTTON_HEIGHT, Component.literal("Field2"));

		String confirmLabel;
		if (type == Modal.NEW_REPO) {
			modalField1.setPlaceholder(Component.literal("Repository name"));
			modalField2.setPlaceholder(Component.literal("Description (optional)"));
			confirmLabel = "Create";
		} else if (type == Modal.RELEASE) {
			// Releases the current branch's head commit; pick a different commit on the website.
			modalField1.setPlaceholder(Component.literal("Tag name (e.g. v1.0)"));
			modalField2.setPlaceholder(Component.literal("Path to thumbnail image"));
			confirmLabel = "Release";
		} else {
			// Pre-fill the path field with the default schematics directory for convenience.
			String defaultPath = FabricLoader.getInstance().getGameDir().resolve("schematics").toString();
			modalField1.setValue(defaultPath);
			modalField1.setPlaceholder(Component.literal("Path to .litematic file"));
			modalField2.setPlaceholder(Component.literal("Commit message"));
			confirmLabel = "Push";
		}

		int buttonY    = boxY + MODAL_HEIGHT - PADDING - BUTTON_HEIGHT;
		int buttonWidth = 80;
		modalConfirmButton = new CustomButton(boxX + MODAL_WIDTH - PADDING - buttonWidth * 2 - PADDING, buttonY, buttonWidth, BUTTON_HEIGHT, Component.literal(confirmLabel), button -> confirmModal());
		modalCancelButton  = new CustomButton(boxX + MODAL_WIDTH - PADDING - buttonWidth, buttonY, buttonWidth, BUTTON_HEIGHT, Component.literal("Cancel"), button -> closeModal());
	}

	private void closeModal() {
		CustomTextField.restoreMinecraftCharCallback();
		this.modal         = Modal.NONE;
		modalField1        = null;
		modalField2        = null;
		modalConfirmButton = null;
		modalCancelButton  = null;
	}

	/** Validates modal inputs and dispatches the appropriate network action. */
	private void confirmModal() {
		if (modal == Modal.NEW_REPO) {
			String name        = modalField1.getValue().trim();
			String description = modalField2.getValue().trim();
			if (name.isEmpty()) {
				toastManager.showError("Repository name is required");
				return;
			}
			closeModal();
			createRepo(name, description);
		} else if (modal == Modal.PUSH) {
			String path    = modalField1.getValue().trim();
			String message = modalField2.getValue().trim();
			if (path.isEmpty() || message.isEmpty()) {
				toastManager.showError("File path and commit message are required");
				return;
			}
			File file = new File(path);
			if (!file.exists() || !file.isFile()) {
				toastManager.showError("File not found: " + path);
				return;
			}
			closeModal();
			pushCommitFile(file, message);
		} else if (modal == Modal.RELEASE) {
			String tagName       = modalField1.getValue().trim();
			String thumbnailPath = modalField2.getValue().trim();
			if (tagName.isEmpty() || thumbnailPath.isEmpty()) {
				toastManager.showError("Tag name and thumbnail path are required");
				return;
			}
			File thumbnailFile = new File(thumbnailPath);
			if (!thumbnailFile.exists() || !thumbnailFile.isFile()) {
				toastManager.showError("Thumbnail file not found: " + thumbnailPath);
				return;
			}
			String headCommitId = null;
			if (selectedRepo.branches != null) {
				for (GitBranchDto branch : selectedRepo.branches) {
					if (branch.name.equals(selectedBranchName)) {
						headCommitId = branch.headCommitId;
						break;
					}
				}
			}
			if (headCommitId == null) {
				toastManager.showError("This branch has no commits to release yet");
				return;
			}
			closeModal();
			releaseCommit(headCommitId, tagName, thumbnailFile);
		}
	}

	// -------------------------------------------------------------------------
	// Rendering
	// -------------------------------------------------------------------------

	private void renderMainLayout(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		int leftPanelWidth = getLeftPanelWidth();
		int rightPanelX   = getRightPanelX();
		int rightPanelWidth = getRightPanelWidth();

		// Vertical divider between the two panels.
		context.fill(leftPanelWidth, 0, leftPanelWidth + UITheme.Dimensions.BORDER_WIDTH, this.height, UITheme.Colors.PANEL_BORDER);

		backButton.extractRenderState(context, mouseX, mouseY, delta);
		settingsButton.extractRenderState(context, mouseX, mouseY, delta);
		refreshButton.extractRenderState(context, mouseX, mouseY, delta);
		newRepoButton.extractRenderState(context, mouseX, mouseY, delta);

		int listY = PADDING * 2 + BUTTON_HEIGHT + PADDING;
		if (loadingRepos) {
			context.text(this.font, "Loading...", PADDING, listY, UITheme.Colors.TEXT_MUTED);
		} else if (repos.isEmpty()) {
			context.text(this.font, "No repositories yet.", PADDING, listY, UITheme.Colors.TEXT_MUTED);
		} else {
			for (CustomButton row : repoRowButtons) {
				row.extractRenderState(context, mouseX, mouseY, delta);
			}
		}

		if (getTotalRepoPages() > 1) {
			prevRepoPageButton.extractRenderState(context, mouseX, mouseY, delta);
			nextRepoPageButton.extractRenderState(context, mouseX, mouseY, delta);
			String pageText = repoPage + " / " + getTotalRepoPages();
			context.text(this.font, pageText, PADDING + 135, this.height - PADDING - BUTTON_HEIGHT + 6, UITheme.Colors.TEXT_SUBTITLE);
		}

		if (selectedRepo == null) {
			context.text(this.font, "Select a repository to view its history.", rightPanelX, PADDING * 2 + BUTTON_HEIGHT + PADDING, UITheme.Colors.TEXT_MUTED);
			return;
		}

		captureZoneButton.setMessage(Component.literal(
			ZoneSelectionManager.hasSavedZone(selectedRepo.id) ? "Redefine Zone" : "Set Zone"));
		captureZoneButton.extractRenderState(context, mouseX, mouseY, delta);
		releaseButton.extractRenderState(context, mouseX, mouseY, delta);
		pushButton.extractRenderState(context, mouseX, mouseY, delta);

		int previewY = PADDING * 2 + BUTTON_HEIGHT + PADDING;
		renderZonePreview(context, mouseX, mouseY, delta, rightPanelX, previewY, rightPanelWidth);

		int infoY = previewY + PREVIEW_HEIGHT + PADDING;
		context.text(this.font, truncate(selectedRepo.name, rightPanelWidth), rightPanelX, infoY, UITheme.Colors.TEXT_PRIMARY);
		infoY += UITheme.Typography.LINE_HEIGHT;

		if (selectedRepo.description != null && !selectedRepo.description.isEmpty()) {
			context.text(this.font, truncate(selectedRepo.description, rightPanelWidth), rightPanelX, infoY, UITheme.Colors.TEXT_SUBTITLE);
			infoY += UITheme.Typography.LINE_HEIGHT;
		}

		String displayBranch = selectedBranchName != null ? selectedBranchName : selectedRepo.defaultBranchName;
		context.text(this.font, "Branch: " + displayBranch, rightPanelX, infoY, UITheme.Colors.TEXT_MUTED);
		infoY += UITheme.Typography.LINE_HEIGHT + PADDING;

		context.text(this.font, "Commit History", rightPanelX, infoY, UITheme.Colors.TEXT_PRIMARY);
		infoY += UITheme.Typography.LINE_HEIGHT + 2;

		if (loadingCommits) {
			context.text(this.font, "Loading...", rightPanelX, infoY, UITheme.Colors.TEXT_MUTED);
		} else if (commits.isEmpty()) {
			context.text(this.font, "No commits yet.", rightPanelX, infoY, UITheme.Colors.TEXT_MUTED);
		} else {
			commitRowBounds.clear();
			for (GitCommitDto commit : commits) {
				int rowTop = infoY - 2;
				int rowHeight = UITheme.Typography.LINE_HEIGHT * 2 + 4;
				boolean hovered = mouseX >= rightPanelX && mouseX < rightPanelX + rightPanelWidth
					&& mouseY >= rowTop && mouseY < rowTop + rowHeight;
				if (hovered) {
					context.fill(rightPanelX, rowTop, rightPanelX + rightPanelWidth, rowTop + rowHeight, UITheme.Colors.CONTAINER_BG);
				}
				commitRowBounds.add(new int[]{rightPanelX, rowTop, rightPanelWidth, rowHeight});

				String line1 = shortId(commit.id) + "  " + truncate(commit.message, rightPanelWidth - 60);
				context.text(this.font, line1, rightPanelX, infoY, UITheme.Colors.TEXT_PRIMARY);
				infoY += UITheme.Typography.LINE_HEIGHT;

				String line2 = commit.authorUsername + "  " + formatDate(commit.committedDate);
				context.text(this.font, line2, rightPanelX, infoY, UITheme.Colors.TEXT_MUTED);
				infoY += UITheme.Typography.LINE_HEIGHT + 2;
			}
		}

		if (commitTotalPages > 1) {
			prevCommitPageButton.extractRenderState(context, mouseX, mouseY, delta);
			nextCommitPageButton.extractRenderState(context, mouseX, mouseY, delta);
			String pageText = commitPage + " / " + commitTotalPages;
			context.text(this.font, pageText, rightPanelX + 135, this.height - PADDING - BUTTON_HEIGHT + 6, UITheme.Colors.TEXT_SUBTITLE);
		}

		if (prevBranchButton.visible) prevBranchButton.extractRenderState(context, mouseX, mouseY, delta);
		if (nextBranchButton.visible) nextBranchButton.extractRenderState(context, mouseX, mouseY, delta);
		if (modulesButton.visible) modulesButton.extractRenderState(context, mouseX, mouseY, delta);
	}

	/**
	 * Renders the always-visible 3D preview of the selected repo's saved zone, using LD's own
	 * {@code SchematicRenderer} - the same viewer LD shows when you double-click a file in its
	 * file manager.
	 */
	private void renderZonePreview(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, int x, int y, int width) {
		context.fill(x, y, x + width, y + PREVIEW_HEIGHT, UITheme.Colors.CONTAINER_BG);

		switch (previewStatus) {
			case NO_WORLD -> context.centeredText(this.font, "Open this in-world to preview the zone", x + width / 2, y + PREVIEW_HEIGHT / 2, UITheme.Colors.TEXT_MUTED);
			case NO_ZONE -> context.centeredText(this.font, "No zone set for this repository", x + width / 2, y + PREVIEW_HEIGHT / 2, UITheme.Colors.TEXT_MUTED);
			case LOADING -> context.centeredText(this.font, "Loading preview...", x + width / 2, y + PREVIEW_HEIGHT / 2, 0xFFFFAA00);
			case TOO_LARGE -> context.centeredText(this.font, "Zone is too large to preview", x + width / 2, y + PREVIEW_HEIGHT / 2, 0xFFFF4444);
			case EMPTY -> context.centeredText(this.font, "No blocks in this zone", x + width / 2, y + PREVIEW_HEIGHT / 2, UITheme.Colors.TEXT_MUTED);
			case READY -> {
				if (zonePreviewRenderer.isBuilding()) {
					context.centeredText(this.font, "Loading preview...", x + width / 2, y + PREVIEW_HEIGHT / 2, 0xFFFFAA00);
				} else if (zonePreviewRenderer.isEmpty()) {
					context.centeredText(this.font, "No blocks in this zone", x + width / 2, y + PREVIEW_HEIGHT / 2, UITheme.Colors.TEXT_MUTED);
				} else {
					zonePreviewRenderer.render(context, x, y, width, PREVIEW_HEIGHT, mouseX, mouseY);
				}
			}
		}
	}

	private void renderModal(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		// Dim the background to focus attention on the modal.
		context.fill(0, 0, this.width, this.height, UITheme.Colors.OVERLAY_BG);

		int boxX       = (this.width - MODAL_WIDTH) / 2;
		int boxY       = (this.height - MODAL_HEIGHT) / 2;
		int borderWidth = UITheme.Dimensions.BORDER_WIDTH;

		context.fill(boxX, boxY, boxX + MODAL_WIDTH, boxY + MODAL_HEIGHT, UITheme.Colors.CONTAINER_BG);
		context.fill(boxX, boxY, boxX + MODAL_WIDTH, boxY + borderWidth, UITheme.Colors.PANEL_BORDER);
		context.fill(boxX, boxY + MODAL_HEIGHT - borderWidth, boxX + MODAL_WIDTH, boxY + MODAL_HEIGHT, UITheme.Colors.PANEL_BORDER);
		context.fill(boxX, boxY, boxX + borderWidth, boxY + MODAL_HEIGHT, UITheme.Colors.PANEL_BORDER);
		context.fill(boxX + MODAL_WIDTH - borderWidth, boxY, boxX + MODAL_WIDTH, boxY + MODAL_HEIGHT, UITheme.Colors.PANEL_BORDER);

		String title = modal == Modal.NEW_REPO ? "New Repository" : "Push Commit";
		context.text(this.font, title, boxX + PADDING, boxY + PADDING, UITheme.Colors.TEXT_PRIMARY);

		if (modalField1 != null) modalField1.extractRenderState(context, mouseX, mouseY, delta);
		if (modalField2 != null) modalField2.extractRenderState(context, mouseX, mouseY, delta);
		if (modalConfirmButton != null) modalConfirmButton.extractRenderState(context, mouseX, mouseY, delta);
		if (modalCancelButton != null) modalCancelButton.extractRenderState(context, mouseX, mouseY, delta);
	}

	// -------------------------------------------------------------------------
	// Utility
	// -------------------------------------------------------------------------

	/** Returns the first 8 characters of an ID, or the full string if shorter. */
	private String shortId(String id) {
		if (id == null) {
			return "";
		}
		return id.length() > 8 ? id.substring(0, 8) : id;
	}

	/**
	 * Truncates {@code text} with an ellipsis if it exceeds {@code maxWidth} pixels.
	 *
	 * @param text     string to truncate
	 * @param maxWidth maximum allowed pixel width
	 * @return the original string, or a truncated version ending with {@code "..."}
	 */
	private String truncate(String text, int maxWidth) {
		if (text == null) {
			return "";
		}
		if (this.font.width(text) <= maxWidth) {
			return text;
		}
		String result = text;
		while (result.length() > 1 && this.font.width(result + "...") > maxWidth) {
			result = result.substring(0, result.length() - 1);
		}
		return result + "...";
	}

	/**
	 * Extracts the date portion of an ISO-8601 timestamp (everything before the
	 * {@code 'T'} separator).
	 *
	 * @param isoDate ISO-8601 date-time string, or {@code null}
	 * @return the date component, or {@code ""} if the input is null or has no 'T'
	 */
	private String formatDate(String isoDate) {
		if (isoDate == null) {
			return "";
		}
		int idx = isoDate.indexOf('T');
		return idx > 0 ? isoDate.substring(0, idx) : isoDate;
	}
}

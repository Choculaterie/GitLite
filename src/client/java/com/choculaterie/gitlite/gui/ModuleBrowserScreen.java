package com.choculaterie.gitlite.gui;

import com.choculaterie.vanilib.gui.widget.CustomButton;
import com.choculaterie.vanilib.gui.widget.CustomTextField;
import com.choculaterie.vanilib.gui.widget.ToastManager;
import com.choculaterie.gitlite.model.GitModuleListItemDto;
import com.choculaterie.gitlite.model.GitRepoDetailDto;
import com.choculaterie.gitlite.network.GitLiteNetworkManager;
import com.choculaterie.vanilib.gui.theme.UITheme;
import com.choculaterie.util.LitematicParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public class ModuleBrowserScreen extends Screen {
    private static final int PADDING = 10;
    private static final int BUTTON_H = 20;
    private static final int ROW_H = 22;
    private static final int MODULES_PER_PAGE = 8;
    private static final int MODAL_W = 310;
    private static final int MODAL_H = 70;

    private final Screen parent;
    private final GitRepoDetailDto repo;
    private final String branchName;
    private ToastManager toastManager;

    private final List<GitModuleListItemDto> modules = new ArrayList<>();
    private final List<CustomButton> moduleRowBtns = new ArrayList<>();
    private int page = 1;
    private int totalPages = 1;
    private boolean loading = false;

    private GitModuleListItemDto selectedModule = null;
    private List<String> previewLines = null;
    private boolean previewing = false;

    private CustomButton backBtn;
    private CustomButton prevPageBtn;
    private CustomButton nextPageBtn;
    private CustomButton previewBtn;
    private CustomButton importBtn;

    private boolean importModalOpen = false;
    private CustomTextField commitMsgField;
    private CustomButton confirmImportBtn;
    private CustomButton cancelImportBtn;

    public ModuleBrowserScreen(Screen parent, GitRepoDetailDto repo, String branchName) {
        super(Component.literal("Modules - " + repo.name + "/" + branchName));
        this.parent = parent;
        this.repo = repo;
        this.branchName = branchName;
    }

    @Override
    protected void init() {
        super.init();
        if (toastManager == null) toastManager = new ToastManager(this.minecraft);

        backBtn = new CustomButton(PADDING, PADDING, BUTTON_H, BUTTON_H, Component.literal("←"), b -> minecraft.setScreen(parent));

        int paginationY = this.height - PADDING - BUTTON_H;
        prevPageBtn = new CustomButton(PADDING, paginationY, 60, BUTTON_H, Component.literal("< Prev"), b -> changePage(-1));
        nextPageBtn = new CustomButton(PADDING + 65, paginationY, 60, BUTTON_H, Component.literal("Next >"), b -> changePage(1));

        int rx = this.width / 2 + PADDING;
        int actionY = this.height - PADDING * 2 - BUTTON_H * 2 - 4;
        previewBtn = new CustomButton(rx, actionY, 80, BUTTON_H, Component.literal("Preview"), b -> previewSelected());
        importBtn = new CustomButton(rx + 85, actionY, 70, BUTTON_H, Component.literal("Import"), b -> openImportModal());

        int mfX = (this.width - MODAL_W) / 2;
        int mfY = this.height / 2 - 10 + 6;
        commitMsgField = new CustomTextField(this.minecraft, mfX, mfY, MODAL_W, BUTTON_H, Component.literal("Commit message"));
        commitMsgField.setPlaceholder(Component.literal("Commit message"));
        confirmImportBtn = new CustomButton(mfX, mfY + BUTTON_H + 4, 95, BUTTON_H, Component.literal("Confirm"), b -> confirmImport());
        cancelImportBtn = new CustomButton(mfX + 100, mfY + BUTTON_H + 4, 95, BUTTON_H, Component.literal("Cancel"), b -> closeImportModal());

        updatePaginationButtons();
        if (modules.isEmpty() && !loading) {
            loadModules();
        } else {
            rebuildModuleRows();
        }
    }

    private void changePage(int delta) {
        int next = page + delta;
        if (next >= 1 && next <= totalPages) {
            page = next;
            loadModules();
        }
    }

    private void loadModules() {
        loading = true;
        GitLiteNetworkManager.listModules(page, MODULES_PER_PAGE).thenAccept(result -> {
            if (minecraft != null) minecraft.execute(() -> {
                loading = false;
                modules.clear();
                if (result != null && result.items != null) {
                    modules.addAll(result.items);
                    totalPages = Math.max(1, result.totalPages);
                }
                selectedModule = null;
                previewLines = null;
                rebuildModuleRows();
                updatePaginationButtons();
            });
        }).exceptionally(err -> {
            if (minecraft != null) minecraft.execute(() -> {
                loading = false;
                toastManager.showError("Failed to load modules");
            });
            return null;
        });
    }

    private void rebuildModuleRows() {
        moduleRowBtns.clear();
        int listW = this.width / 2 - PADDING * 2;
        for (int i = 0; i < modules.size(); i++) {
            final GitModuleListItemDto m = modules.get(i);
            int y = PADDING * 2 + BUTTON_H + i * ROW_H;
            String label = truncate(m.name + "  by " + m.authorName, listW);
            moduleRowBtns.add(new CustomButton(PADDING, y, listW, ROW_H - 2, Component.literal(label), b -> selectModule(m)));
        }
    }

    private void selectModule(GitModuleListItemDto m) {
        selectedModule = m;
        previewLines = null;
    }

    private void previewSelected() {
        if (selectedModule == null || previewing) return;
        previewing = true;
        toastManager.showInfo("Downloading preview...");
        final String mid = selectedModule.id;
        GitLiteNetworkManager.downloadModuleFile(mid).thenAccept(bytes -> {
            if (minecraft != null) minecraft.execute(() -> {
                previewing = false;
                if (bytes == null || bytes.length == 0) {
                    toastManager.showError("Empty file received");
                    return;
                }
                try {
                    File cacheDir = new File(FabricLoader.getInstance().getGameDir().toFile(), "mods/gitlite/cache");
                    cacheDir.mkdirs();
                    File temp = new File(cacheDir, mid + ".litematic");
                    try (FileOutputStream fos = new FileOutputStream(temp)) {
                        fos.write(bytes);
                    }
                    List<LitematicParser.BlockCount> counts = LitematicParser.parseBlockCounts(temp);
                    List<String> lines = new ArrayList<>();
                    int max = Math.min(10, counts.size());
                    for (int i = 0; i < max; i++) {
                        var bc = counts.get(i);
                        String name = bc.blockId.replace("minecraft:", "").replace("_", " ");
                        lines.add(name + " ×" + bc.count);
                    }
                    if (lines.isEmpty()) lines.add("No blocks found");
                    previewLines = lines;
                } catch (Exception e) {
                    toastManager.showError("Preview failed");
                }
            });
        }).exceptionally(err -> {
            if (minecraft != null) minecraft.execute(() -> {
                previewing = false;
                toastManager.showError("Download failed");
            });
            return null;
        });
    }

    private void openImportModal() {
        if (selectedModule == null) { toastManager.showError("Select a module first"); return; }
        importModalOpen = true;
        commitMsgField.setValue("Import " + selectedModule.name);
        commitMsgField.setFocused(true);
    }

    private void closeImportModal() {
        importModalOpen = false;
        commitMsgField.setFocused(false);
        CustomTextField.restoreMinecraftCharCallback();
    }

    private void confirmImport() {
        String msg = commitMsgField.getValue().trim();
        if (msg.isEmpty()) { toastManager.showError("Message required"); return; }
        if (selectedModule == null) return;
        String mid = selectedModule.id;
        closeImportModal();
        toastManager.showInfo("Importing...");
        GitLiteNetworkManager.importModule(repo.id, branchName, mid, msg).thenAccept(commit -> {
            if (minecraft != null) minecraft.execute(() ->
                    toastManager.showSuccess("Imported: " + (commit.id != null ? commit.id.substring(0, Math.min(8, commit.id.length())) : "?")));
        }).exceptionally(err -> {
            if (minecraft != null) minecraft.execute(() ->
                    toastManager.showError("Import failed"));
            return null;
        });
    }

    private void updatePaginationButtons() {
        if (prevPageBtn != null) prevPageBtn.active = page > 1;
        if (nextPageBtn != null) nextPageBtn.active = page < totalPages;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, UITheme.Colors.PANEL_BG);
        ctx.fill(this.width / 2, 0, this.width / 2 + 1, this.height, UITheme.Colors.PANEL_BORDER);

        ctx.centeredText(font, title, this.width / 2, 4, UITheme.Colors.TEXT_PRIMARY);
        backBtn.extractRenderState(ctx, mouseX, mouseY, delta);

        int listY = PADDING * 2 + BUTTON_H;
        if (loading) {
            ctx.text(font, Component.literal("Loading..."), PADDING, listY, UITheme.Colors.TEXT_MUTED);
        } else if (modules.isEmpty()) {
            ctx.text(font, Component.literal("No modules available."), PADDING, listY, UITheme.Colors.TEXT_MUTED);
        } else {
            for (CustomButton row : moduleRowBtns) {
                row.extractRenderState(ctx, mouseX, mouseY, delta);
            }
        }

        if (totalPages > 1) {
            prevPageBtn.extractRenderState(ctx, mouseX, mouseY, delta);
            nextPageBtn.extractRenderState(ctx, mouseX, mouseY, delta);
            ctx.text(font, Component.literal(page + "/" + totalPages),
                    PADDING + 135, this.height - PADDING - BUTTON_H + 6, UITheme.Colors.TEXT_MUTED);
        }

        int rx = this.width / 2 + PADDING;
        int rw = this.width - rx - PADDING;
        if (selectedModule != null) {
            int y = PADDING * 2 + BUTTON_H;
            ctx.text(font, Component.literal(truncate(selectedModule.name, rw)), rx, y, UITheme.Colors.TEXT_PRIMARY);
            y += 14;
            ctx.text(font, Component.literal("by " + selectedModule.authorName), rx, y, UITheme.Colors.TEXT_SUBTITLE);
            y += 12;
            if (selectedModule.description != null && !selectedModule.description.isEmpty()) {
                ctx.text(font, Component.literal(truncate(selectedModule.description, rw)), rx, y, UITheme.Colors.TEXT_MUTED);
                y += 12;
            }
            y += 4;
            ctx.text(font, Component.literal("Downloads: " + selectedModule.downloadCount), rx, y, UITheme.Colors.TEXT_MUTED);

            previewBtn.extractRenderState(ctx, mouseX, mouseY, delta);
            importBtn.extractRenderState(ctx, mouseX, mouseY, delta);

            if (previewLines != null) {
                int py = this.height - PADDING * 2 - BUTTON_H * 2 + 4;
                ctx.text(font, Component.literal("Block preview:"), rx, py, UITheme.Colors.TEXT_SUBTITLE);
                py += 12;
                for (String line : previewLines) {
                    ctx.text(font, Component.literal(line), rx, py, UITheme.Colors.TEXT_MUTED);
                    py += 10;
                }
            } else if (previewing) {
                ctx.text(font, Component.literal("Loading preview..."), rx,
                        this.height - PADDING * 2 - BUTTON_H * 2 + 4, UITheme.Colors.TEXT_MUTED);
            }
        } else {
            ctx.centeredText(font, Component.literal("Select a module on the left"),
                    this.width * 3 / 4, this.height / 2, UITheme.Colors.TEXT_MUTED);
        }

        if (importModalOpen) {
            int mw = MODAL_W, mh = MODAL_H, mx = (this.width - mw) / 2, my = this.height / 2 - 10;
            ctx.fill(mx - 1, my - 24, mx + mw + 1, my + mh, UITheme.Colors.CONTAINER_BG);
            ctx.text(font, Component.literal("Commit message:"), mx, my - 18, UITheme.Colors.TEXT_PRIMARY);
            commitMsgField.extractRenderState(ctx, mouseX, mouseY, delta);
            confirmImportBtn.extractRenderState(ctx, mouseX, mouseY, delta);
            cancelImportBtn.extractRenderState(ctx, mouseX, mouseY, delta);
        }

        if (toastManager != null) toastManager.render(ctx, delta, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mx = click.x(), my = click.y();

        if (toastManager != null && toastManager.mouseClicked(mx, my)) return true;

        if (importModalOpen) {
            commitMsgField.setFocused(commitMsgField.isMouseOver(mx, my));
            if (commitMsgField.mouseClicked(click, doubled)) return true;
            if (confirmImportBtn.mouseClicked(click, doubled)) return true;
            if (cancelImportBtn.mouseClicked(click, doubled)) return true;
            return true;
        }

        if (backBtn.mouseClicked(click, doubled)) return true;
        for (CustomButton row : moduleRowBtns) {
            if (row.mouseClicked(click, doubled)) return true;
        }
        if (prevPageBtn.mouseClicked(click, doubled)) return true;
        if (nextPageBtn.mouseClicked(click, doubled)) return true;
        if (selectedModule != null) {
            if (previewBtn.mouseClicked(click, doubled)) return true;
            if (importBtn.mouseClicked(click, doubled)) return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean shouldCloseOnEsc() { return !importModalOpen; }

    @Override
    public void onClose() {
        CustomTextField.restoreMinecraftCharCallback();
        if (minecraft != null) minecraft.setScreen(parent);
    }

    private String truncate(String s, int maxPx) {
        if (s == null) return "";
        if (font.width(s) <= maxPx) return s;
        while (s.length() > 1 && font.width(s + "…") > maxPx) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}

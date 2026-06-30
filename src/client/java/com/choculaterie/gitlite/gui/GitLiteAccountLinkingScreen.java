package com.choculaterie.gitlite.gui;

import com.choculaterie.gitlite.config.GitLiteSettings;
import com.choculaterie.vanilib.gui.theme.UITheme;
import com.choculaterie.vanilib.gui.widget.CustomButton;
import com.choculaterie.vanilib.gui.widget.ToastManager;
import com.choculaterie.gitlite.network.GitLiteFlowNetworkManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Guides the user through linking their Choculaterie account to the Minecraft client.
 *
 * <p>The linking workflow is a browser-based OAuth flow managed by
 * {@link GitLiteFlowNetworkManager}:
 * <ol>
 *   <li>The user clicks "Link Account" which calls
 *       {@link GitLiteFlowNetworkManager#initiateOAuthFlow}.</li>
 *   <li>A browser window is opened to the returned authorization URL. If the browser
 *       does not open automatically, a "Copy URL" button is shown.</li>
 *   <li>A background {@link ScheduledExecutorService} polls the flow status every 2 s
 *       until approved, cancelled, or expired (see {@link #startPolling}).</li>
 *   <li>On approval the server may optionally require Minecraft account verification by
 *       joining a server and sending a link command (see {@link #autoJoinServerAndLink}).</li>
 *   <li>Once fully completed the API key is persisted via {@link GitLiteSettings} and the
 *       user is forwarded to {@link GitLiteScreen}.</li>
 * </ol>
 *
 * <p>If an account is already linked, the screen shows a "Reset" button to unlink it.
 */
public class GitLiteAccountLinkingScreen extends Screen {

    private static final int PADDING  = 6;
    private static final int BTN_SIZE = 20;

    private final Screen parent;
    private final GitLiteFlowNetworkManager networkManager = new GitLiteFlowNetworkManager();
    private ToastManager toastManager;

    private String currentFlowId    = null;
    private String pendingLinkCode  = null;
    private boolean isLinking       = false;
    private String linkingStatus    = "";
    private String pendingAuthUrl   = null;
    private ScheduledExecutorService pollExecutor = null;
    private CustomButton linkBtn    = null;
    private CustomButton copyUrlBtn = null;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates the account-linking screen.
     *
     * @param parent the screen to return to after linking or on back navigation
     */
    public GitLiteAccountLinkingScreen(Screen parent) {
        super(Component.literal("Link Choculaterie Account"));
        this.parent = parent;
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void init() {
        if (toastManager == null) {
            toastManager = new ToastManager(this.minecraft);
        }

        addRenderableWidget(
                new CustomButton(PADDING, PADDING, BTN_SIZE, BTN_SIZE,
                        Component.literal("←"), b -> goBack()));

        boolean hasKey = GitLiteSettings.getInstance().hasApiKey();

        int cx = this.width / 2, btnW = 100;
        int btnY = this.height / 2 - 10;

        linkBtn = new CustomButton(cx - btnW / 2, btnY, btnW, BTN_SIZE,
                Component.literal(hasKey ? "Reset" : "Link Account"),
                b -> handleLinkOrReset(hasKey));
        addRenderableWidget(linkBtn);

        copyUrlBtn = new CustomButton(cx - btnW / 2, btnY, btnW, BTN_SIZE,
                Component.literal("Copy URL"), b -> copyAuthUrl());
        copyUrlBtn.visible = false;
        addRenderableWidget(copyUrlBtn);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, UITheme.Colors.PANEL_BG);
        super.extractRenderState(context, mouseX, mouseY, delta);
        int cx   = this.width / 2;
        int btnY = this.height / 2 - 10;

        context.centeredText(font, title, cx, 10, 0xFFFFFFFF);

        boolean hasKey = GitLiteSettings.getInstance().hasApiKey();

        if (hasKey && !isLinking) {
            context.centeredText(font, Component.literal("§aAccount linked ✓"), cx, btnY - 20, 0xFFFFFFFF);
            context.centeredText(font, Component.literal("Reset to unlink and connect a different account."),
                    cx, btnY + 30, 0xFF888888);
        } else if (!isLinking) {
            int stepY = btnY + 32, lineH = 12;
            context.centeredText(font, Component.literal("How it works:"), cx, stepY, 0xFF999999);
            stepY += lineH + 4;
            context.centeredText(font, Component.literal("1. A browser window will open. Sign in and click Approve."),
                    cx, stepY, 0xFFCCCCCC);
            stepY += lineH;
            context.centeredText(font, Component.literal("2. The game will briefly join a server to verify your Minecraft account."),
                    cx, stepY, 0xFFCCCCCC);
            stepY += lineH;
            context.centeredText(font, Component.literal("3. Once verified, you're ready to push your schematics!"),
                    cx, stepY, 0xFFCCCCCC);
        } else {
            if (!linkingStatus.isEmpty()) {
                context.centeredText(font, Component.literal(linkingStatus), cx, btnY - 20, 0xFF88FF88);
            }
            if (pendingAuthUrl != null) {
                context.centeredText(font, Component.literal("Browser didn't open? Copy the URL and paste it manually."),
                        cx, btnY + 30, 0xFF888888);
            }
        }

        if (toastManager != null) toastManager.render(context, delta, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (toastManager != null && toastManager.mouseClicked(click.x(), click.y())) return true;
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        goBack();
    }

    // -------------------------------------------------------------------------
    // Button handlers
    // -------------------------------------------------------------------------

    /**
     * Either clears the stored API key (reset path) or starts the OAuth flow
     * (link path) depending on whether a key was already present.
     *
     * @param hadKey {@code true} if an API key was present when this screen opened
     */
    private void handleLinkOrReset(boolean hadKey) {
        if (hadKey) {
            GitLiteSettings.getInstance().setApiKey("");
            minecraft.setScreen(new GitLiteAccountLinkingScreen(parent));
        } else {
            startOAuthFlow();
        }
    }

    private void goBack() {
        if (minecraft == null) return;
        stopPolling();
        minecraft.setScreen(parent);
    }

    private void copyAuthUrl() {
        if (pendingAuthUrl != null && minecraft.keyboardHandler != null) {
            minecraft.keyboardHandler.setClipboard(pendingAuthUrl);
            toastManager.showSuccess("URL copied! Paste it in your browser.");
        }
    }

    // -------------------------------------------------------------------------
    // OAuth flow
    // -------------------------------------------------------------------------

    /** Initiates the server-side OAuth flow and opens the browser authorization URL. */
    private void startOAuthFlow() {
        if (isLinking) return;
        isLinking = true;
        linkingStatus = "Initiating...";

        networkManager.initiateOAuthFlow("GitLite Mod").whenComplete((json, err) -> {
            if (err != null) {
                runOnClient(() -> { isLinking = false; linkingStatus = ""; });
                return;
            }
            try {
                currentFlowId = json.has("flowId") ? json.get("flowId").getAsString() : null;
                int expiresIn = json.has("expiresInSeconds") ? json.get("expiresInSeconds").getAsInt() : 300;
                if (currentFlowId == null) {
                    runOnClient(() -> { isLinking = false; linkingStatus = ""; });
                    return;
                }
                String authUrl = networkManager.getOAuthAuthorizeUrl(currentFlowId);
                runOnClient(() -> {
                    pendingAuthUrl = authUrl;
                    if (linkBtn != null) linkBtn.visible = false;
                    if (copyUrlBtn != null) copyUrlBtn.visible = true;
                    linkingStatus = "Waiting for approval...";
                    // Attempt to open the browser; silently ignore if it fails (user can copy manually).
                    try {
                        net.minecraft.util.Util.getPlatform().openUri(new java.net.URI(authUrl));
                    } catch (Exception ignored) {}
                });
                startPolling(currentFlowId, expiresIn);
            } catch (Exception e) {
                runOnClient(() -> { isLinking = false; linkingStatus = ""; });
            }
        });
    }

    /**
     * Schedules a background task that polls the flow status every 2 seconds until
     * approved, cancelled, or the flow expires.
     *
     * @param flowId         the flow identifier to poll
     * @param timeoutSeconds maximum seconds before the flow expires server-side
     */
    private void startPolling(String flowId, int timeoutSeconds) {
        stopPolling();
        pollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GitLite-OAuth-Poll");
            t.setDaemon(true);
            return t;
        });

        final int[] attempts = {0};
        final int maxAttempts = timeoutSeconds / 2;
        final var mc = net.minecraft.client.Minecraft.getInstance();

        pollExecutor.scheduleAtFixedRate(() -> {
            if (++attempts[0] >= maxAttempts) {
                mc.execute(() -> { stopPolling(); isLinking = false; linkingStatus = ""; });
                return;
            }
            networkManager.getOAuthFlowStatus(flowId).whenComplete((json, err) -> {
                if (err != null) return;
                try { handlePollResponse(json, mc); } catch (Exception ignored) {}
            });
        }, 0, 2, TimeUnit.SECONDS);
    }

    /**
     * Dispatches a single poll response to the appropriate state-transition handler.
     *
     * @param json the JSON status object from the server
     * @param mc   the Minecraft client instance (for thread marshalling)
     */
    private void handlePollResponse(com.google.gson.JsonObject json, net.minecraft.client.Minecraft mc) {
        String status = json.has("status") ? json.get("status").getAsString() : "pending";
        switch (status) {
            case "expired"   -> mc.execute(() -> { stopPolling(); isLinking = false; linkingStatus = ""; resetFlowUI(); });
            case "cancelled" -> {
                mc.execute(() -> { stopPolling(); isLinking = false; linkingStatus = "§cCancelled"; resetFlowUI(); });
                // Auto-clear the "Cancelled" message after 3 s so the UI returns to normal.
                CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS)
                        .execute(() -> mc.execute(() -> linkingStatus = ""));
            }
            case "pending"   -> mc.execute(() -> linkingStatus = "Waiting for approval...");
            case "completed" -> handleCompleted(json, mc);
        }
    }

    /**
     * Processes a {@code "completed"} flow response, handling the three possible
     * completion sub-states: fully linked, Minecraft-link-command required, or
     * waiting for the in-game link command to be acknowledged.
     */
    private void handleCompleted(com.google.gson.JsonObject json, net.minecraft.client.Minecraft mc) {
        // GitLite authenticates against the general mod "apiKey" (same field LitematicDownloader
        // uses), NOT the SaveManager-specific "saveKey" - the flow now returns both.
        String apiKey = json.has("apiKey") && !json.get("apiKey").isJsonNull() ? json.get("apiKey").getAsString() : null;
        if (apiKey == null) return;

        boolean isMinecraftLinked  = json.has("isMinecraftLinked") && json.get("isMinecraftLinked").getAsBoolean();
        boolean linkingComplete    = json.has("minecraftLinkingComplete") && json.get("minecraftLinkingComplete").getAsBoolean();
        String linkCode = json.has("linkCode") && !json.get("linkCode").isJsonNull()
                ? json.get("linkCode").getAsString() : null;

        if (isMinecraftLinked) {
            // Minecraft account was already linked from a previous session.
            stopPolling();
            mc.execute(() -> completeLinking(apiKey));
            return;
        }
        if (linkingComplete) {
            // In-game link command was processed; disconnect from the verification server first.
            stopPolling();
            mc.execute(() -> {
                if (mc.getConnection() != null) {
                    mc.getConnection().getConnection().disconnect(Component.literal("Linking complete"));
                }
                CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS)
                        .execute(() -> mc.execute(() -> completeLinking(apiKey)));
            });
            return;
        }
        // A new link code arrived - join the verification server and send the command.
        if (linkCode != null && !linkCode.equals(pendingLinkCode)) {
            pendingLinkCode = linkCode;
            mc.execute(() -> {
                linkingStatus = "Linking MC account...";
                autoJoinServerAndLink(linkCode);
            });
        }
    }

    /** Shuts down the polling executor if it is still running. */
    private void stopPolling() {
        if (pollExecutor != null && !pollExecutor.isShutdown()) {
            pollExecutor.shutdownNow();
            pollExecutor = null;
        }
    }

    /** Restores the Link Account button and hides Copy URL after a flow ends without approval. */
    private void resetFlowUI() {
        pendingAuthUrl = null;
        if (linkBtn != null) linkBtn.visible = true;
        if (copyUrlBtn != null) copyUrlBtn.visible = false;
    }

    // -------------------------------------------------------------------------
    // Minecraft account verification
    // -------------------------------------------------------------------------

    /**
     * Connects to the Choculaterie verification server and schedules the
     * {@code /link <code>} command to be sent once the player is in-game.
     *
     * @param linkCode the short code returned by the server that must be sent in-game
     */
    private void autoJoinServerAndLink(String linkCode) {
        linkingStatus = "Joining server...";
        final var mc = net.minecraft.client.Minecraft.getInstance();
        try {
            var serverAddress = net.minecraft.client.multiplayer.resolver.ServerAddress
                    .parseString("mc.choculaterie.com");
            var serverInfo = new net.minecraft.client.multiplayer.ServerData(
                    "Choculaterie", "mc.choculaterie.com", net.minecraft.client.multiplayer.ServerData.Type.OTHER);
            net.minecraft.client.gui.screens.ConnectScreen.startConnecting(this, mc, serverAddress, serverInfo, false, null);
            scheduleLinkCommand(mc, linkCode, 6);
        } catch (Exception e) {
            isLinking = false;
            linkingStatus = "";
        }
    }

    /**
     * Schedules the {@code /link} command to be sent after a delay to allow the
     * player to finish connecting. Retries once with a shorter delay if the player
     * is not yet in-game on the first attempt.
     *
     * @param mc           the Minecraft client instance
     * @param linkCode     the link code to include in the command
     * @param delaySeconds initial delay before attempting to send the command
     */
    private void scheduleLinkCommand(net.minecraft.client.Minecraft mc, String linkCode, int delaySeconds) {
        CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS).execute(() -> mc.execute(() -> {
            if (mc.player != null && mc.player.connection != null) {
                linkingStatus = "Sending link command...";
                mc.player.connection.sendCommand("link " + linkCode);
            } else if (delaySeconds == 6) {
                // Player not yet loaded; retry with a shorter delay before giving up.
                scheduleLinkCommand(mc, linkCode, 3);
            }
        }));
    }

    /**
     * Finalises the linking process: persists the API key and navigates to
     * {@link GitLiteScreen}.
     *
     * @param apiKey the API key returned by the server on flow completion
     */
    private void completeLinking(String apiKey) {
        stopPolling();
        isLinking        = false;
        linkingStatus    = "";
        pendingLinkCode  = null;
        currentFlowId    = null;

        GitLiteSettings.getInstance().setApiKey(apiKey);

        var mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new GitLiteScreen(parent)));
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /** Marshals a runnable onto the game thread if Minecraft is available. */
    private void runOnClient(Runnable r) {
        if (minecraft != null) minecraft.execute(r);
    }
}

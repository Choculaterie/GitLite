package com.choculaterie.gitlite.mixin;

import com.choculaterie.gitlite.gui.GitLiteScreen;
import com.choculaterie.vanilib.gui.widget.CustomButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Injects a GitLite button into Litematic Downloader's main screen, immediately to
 * the left of LD's folder / settings / close buttons, that opens {@link GitLiteScreen}.
 *
 * <p>The target class is referenced by string ({@code targets = "…"}) so GitLite
 * compiles successfully even when Litematic Downloader is absent from the classpath;
 * the mixin simply has no effect in that case.
 *
 * <p>Rendering is handled by two complementary injections:
 * <ul>
 *   <li>{@link #gitlite$onRenderBeforeBanner} - fires just before LD checks whether its
 *       {@code ModMessageBanner} is visible, so the banner renders on top of our button
 *       when both are present.</li>
 *   <li>{@link #gitlite$onRenderTail} - fallback that fires at {@code TAIL} in case LD
 *       removes the banner check and the first injection point disappears; only draws if
 *       the button was not already rendered this frame.</li>
 * </ul>
 */
@Mixin(targets = "com.choculaterie.gui.LitematicDownloaderScreen")
public abstract class LitematicDownloaderScreenMixin extends Screen {

    private static final int PADDING     = 10;
    private static final int BUTTON_SIZE = 20;

    @Unique private CustomButton gitlite$gitButton = null;

    /**
     * Guards against double-rendering when both injection points are active in the
     * same frame. Reset to {@code false} at the end of each frame by
     * {@link #gitlite$onRenderTail}.
     */
    @Unique private boolean gitlite$renderedThisFrame = false;

    private LitematicDownloaderScreenMixin(Component title) {
        super(title);
    }

    // -------------------------------------------------------------------------
    // Mixin injections
    // -------------------------------------------------------------------------

    /** Creates the GitLite button after LD has finished laying out its own widgets. */
    @Inject(method = "init", at = @At("TAIL"))
    private void gitlite$onInit(CallbackInfo ci) {
        this.gitlite$gitButton = new CustomButton(
                this.width - PADDING - BUTTON_SIZE * 4,
                PADDING,
                BUTTON_SIZE,
                BUTTON_SIZE,
                Component.literal("🔀"),
                button -> this.minecraft.setScreen(new GitLiteScreen(this))
        );
    }

    /**
     * Renders the GitLite button before LD's banner visibility check so the banner
     * draws on top of our button (correct z-order).
     *
     * <p>{@code require = 0}: if LD removes the banner check this injection silently
     * no-ops and {@link #gitlite$onRenderTail} serves as the fallback.
     */
    @Inject(method = "extractRenderState",
            at = @At(value = "INVOKE",
                     target = "Lcom/choculaterie/gui/widget/ModMessageBanner;isVisible()Z",
                     shift = At.Shift.BEFORE),
            require = 0)
    private void gitlite$onRenderBeforeBanner(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (this.gitlite$gitButton != null) {
            // Pass -1 coords when the banner is covering the button so the button does not
            // register as hovered (no hover highlight or pointer cursor).
            boolean covered = gitlite$isBannerVisible();
            this.gitlite$gitButton.extractRenderState(context, covered ? -1 : mouseX, covered ? -1 : mouseY, delta);
            this.gitlite$renderedThisFrame = true;
        }
    }

    /**
     * Fallback render injection at {@code TAIL} - only draws the button when the
     * primary injection above did not fire (i.e. when the banner field is missing).
     */
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void gitlite$onRenderTail(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (this.gitlite$gitButton != null && !this.gitlite$renderedThisFrame) {
            this.gitlite$gitButton.extractRenderState(context, mouseX, mouseY, delta);
        }
        this.gitlite$renderedThisFrame = false;
    }

    /** Opens {@link GitLiteScreen} when the user left-clicks the GitLite button and no banner is obscuring it. */
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void gitlite$onMouseClicked(MouseButtonEvent click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if (click.button() == 0 && !gitlite$isBannerVisible() && gitlite$isMouseOverGitButton(click.x(), click.y())) {
            this.minecraft.setScreen(new GitLiteScreen(this));
            cir.setReturnValue(true);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Uses reflection to read {@code LitematicDownloaderScreen.modMessageBanner.isVisible()}
     * without a compile-time dependency on LD.
     *
     * @return {@code true} if the banner field exists and reports itself as visible;
     *         {@code false} if the field is absent or reflection fails
     */
    @Unique
    private boolean gitlite$isBannerVisible() {
        try {
            Class<?> clazz = this.getClass();
            while (clazz != null && clazz != Object.class) {
                try {
                    Field f = clazz.getDeclaredField("modMessageBanner");
                    f.setAccessible(true);
                    Object banner = f.get(this);
                    if (banner == null) return false;
                    Method isVisible = banner.getClass().getMethod("isVisible");
                    return (boolean) isVisible.invoke(banner);
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Returns {@code true} if the given screen coordinates fall within the GitLite
     * button's bounds.
     *
     * @param mouseX screen X in GUI-scaled pixels
     * @param mouseY screen Y in GUI-scaled pixels
     */
    @Unique
    private boolean gitlite$isMouseOverGitButton(double mouseX, double mouseY) {
        return this.gitlite$gitButton != null &&
                mouseX >= this.gitlite$gitButton.getX() &&
                mouseX < this.gitlite$gitButton.getX() + this.gitlite$gitButton.getWidth() &&
                mouseY >= this.gitlite$gitButton.getY() &&
                mouseY < this.gitlite$gitButton.getY() + this.gitlite$gitButton.getHeight();
    }
}

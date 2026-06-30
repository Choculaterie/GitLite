package com.choculaterie.gitlite.selection;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * Draws a live, Litematica-style highlight for the zone selection: a bright wireframe outline
 * along every edge of the box, and nothing else - no face fill, no corner markers - matching
 * Litematica's own clean look. Two cases, same drawing code, different colour:
 * <ul>
 *   <li>While actively selecting (armed): pos1 to (pos2, or the block under the crosshair if
 *       pos2 isn't set yet) - cyan, grows live as the player aims.</li>
 *   <li>Otherwise, if the active repo has a saved zone: that zone's saved bounds, rendered
 *       continuously - green, like a persistent Litematica placement - so it stays visible as
 *       a landmark even with no GitLite screen open.</li>
 * </ul>
 *
 * <p>This Minecraft snapshot's rendering internals have moved significantly from the more
 * widely-known {@code WorldRenderEvents} API: that hook is gone, replaced by
 * {@link LevelRenderEvents}, and the old {@code RenderType} constant fields moved onto a
 * separate {@code RenderTypes} class. The outline reuses vanilla's own
 * {@code ShapeRenderer.renderShape} (the same helper vanilla uses for block/shape outlines) over
 * {@code RenderTypes.lines()} rather than hand-building line vertices. Like vanilla's other line
 * render types, this is depth-tested - it does not show through terrain the way Litematica's own
 * (custom-pipeline) overlay does.
 */
public final class ZoneOverlayRenderer {

	private static final int SELECTING_LINE = packColor(0xFF, 0x40, 0xE0, 0xFF);
	private static final int SAVED_LINE = packColor(0xFF, 0x70, 0xFF, 0x70);

	private static final float LINE_WIDTH = 2.5f;

	private ZoneOverlayRenderer() {}

	public static void register() {
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ZoneOverlayRenderer::render);
	}

	private static void render(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context) {
		if (ZoneSelectionManager.isArmed()) {
			renderActiveSelection(context);
		} else {
			renderSavedZone(context);
		}
	}

	private static void renderActiveSelection(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context) {
		BlockPos pos1 = ZoneSelectionManager.getPos1();
		if (pos1 == null) return;

		BlockPos pos2 = ZoneSelectionManager.getPos2();
		if (pos2 == null) {
			pos2 = aimedBlockPos();
			if (pos2 == null) return;
		}

		drawZone(context, pos1, pos2, SELECTING_LINE);
	}

	private static void renderSavedZone(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context) {
		String activeRepoId = ZoneSelectionManager.getActiveRepo();
		if (activeRepoId == null) return;

		ZoneSelectionManager.SavedZone zone = ZoneSelectionManager.getSavedZone(activeRepoId);
		if (zone == null) return;

		drawZone(context, zone.pos1(), zone.pos2(), SAVED_LINE);
	}

	private static void drawZone(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context,
			BlockPos pos1, BlockPos pos2, int lineColor) {
		int minX = Math.min(pos1.getX(), pos2.getX());
		int minY = Math.min(pos1.getY(), pos2.getY());
		int minZ = Math.min(pos1.getZ(), pos2.getZ());
		float sx = Math.abs(pos2.getX() - pos1.getX()) + 1;
		float sy = Math.abs(pos2.getY() - pos1.getY()) + 1;
		float sz = Math.abs(pos2.getZ() - pos1.getZ()) + 1;

		Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		Vec3 camPos = camera.position();

		PoseStack poseStack = context.poseStack();

		poseStack.pushPose();
		poseStack.translate(minX - camPos.x, minY - camPos.y, minZ - camPos.z);

		VertexConsumer lines = context.bufferSource().getBuffer(RenderTypes.lines());
		ShapeRenderer.renderShape(poseStack, lines, Shapes.box(0, 0, 0, sx, sy, sz), 0, 0, 0, lineColor, LINE_WIDTH);

		poseStack.popPose();
	}

	private static BlockPos aimedBlockPos() {
		HitResult hit = Minecraft.getInstance().hitResult;
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			return blockHit.getBlockPos();
		}
		return null;
	}

	/** Packs (a, r, g, b) byte components into a single ARGB int for {@code VertexConsumer.setColor(int)}. */
	private static int packColor(int a, int r, int g, int b) {
		return (a << 24) | (r << 16) | (g << 8) | b;
	}
}

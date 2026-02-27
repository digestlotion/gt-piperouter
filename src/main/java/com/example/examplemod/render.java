package com.example.examplemod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.List;

@Mod.EventBusSubscriber(modid = pointrouter.MOD_ID, value = Dist.CLIENT)
public class render {

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof pointrouteritem)) return;
        PoseStack poseStack = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        VertexConsumer consumer = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);

        CompoundTag nbt = stack.getTag();
        boolean hasStart = nbt != null && nbt.contains("Start");

        if (!hasStart) {
            BlockPos hover = pointrouteritem.getTargetPos(player, player.level());
            LevelRenderer.renderLineBox(poseStack, consumer, new AABB(hover).inflate(-0.002), 1.0f, 1.0f, 0.0f, 0.8f);
        } else {
            BlockPos start = NbtUtils.readBlockPos(nbt.getCompound("Start"));
            BlockPos end = pointrouteritem.getTargetPos(player, player.level());
            List<BlockPos> path = pointrouteritem.findPath(start, end, player);

            for (BlockPos pos : path) {
                float r = pos.equals(start) ? 1.0f : 0.0f;
                float g = 1.0f;
                float b = pos.equals(start) ? 0.0f : 0.5f;
                LevelRenderer.renderLineBox(poseStack, consumer, new AABB(pos).inflate(-0.0002), r, g, b, 0.8f);
            }
        }

        poseStack.popPose();
        mc.renderBuffers().bufferSource().endBatch(RenderType.lines());
    }
}

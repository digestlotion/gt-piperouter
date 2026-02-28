package com.piperouter;

import com.gregtechceu.gtceu.api.block.PipeBlock;
import com.gregtechceu.gtceu.api.pipenet.IPipeNode;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

@Mod.EventBusSubscriber
public class piperouteritem extends Item {

    public piperouteritem(Item.Properties properties) {
        super(properties);
    }

    public static BlockPos getTargetPos(Player player, Level level, boolean isOffset) {
        double reach = player.getBlockReach();

        Vec3 eyePos = player.getEyePosition(1.0F);
        Vec3 viewVec = player.getViewVector(1.0F);
        Vec3 endPos = eyePos.add(viewVec.scale(reach));

        BlockHitResult hitResult = level.clip(new ClipContext(eyePos, endPos,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));

        if (hitResult.getType() == HitResult.Type.BLOCK) {
            if (!isOffset || player.isShiftKeyDown()) {
                return hitResult.getBlockPos();
            }
            return hitResult.getBlockPos().relative(hitResult.getDirection());
        } else {
            return BlockPos.containing(endPos);
        }
    }

    private static Direction getDir(BlockPos a, BlockPos b) {
        return Direction.fromDelta(b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ());
    }

    private static void moveAlongAxis(List<BlockPos> path, BlockPos.MutableBlockPos cursor, int target, char axis) {
        int curr = switch (axis) {
            case 'x' -> cursor.getX();
            case 'y' -> cursor.getY();
            case 'z' -> cursor.getZ();
            default -> 0;
        };

        if (curr == target) return;

        int step = Integer.compare(target, curr);
        while (curr != target) {
            switch (axis) {
                case 'x' -> cursor.move(step, 0, 0);
                case 'y' -> cursor.move(0, step, 0);
                case 'z' -> cursor.move(0, 0, step);
            }
            curr = switch (axis) {
                case 'x' -> cursor.getX();
                case 'y' -> cursor.getY();
                case 'z' -> cursor.getZ();
                default -> 0;
            };
            path.add(cursor.immutable());
        }
    }

    public static List<BlockPos> findPath(BlockPos start, BlockPos end, Player player) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = start.mutable();
        path.add(cursor.immutable());

        int dx = end.getX() - start.getX();
        int dy = end.getY() - start.getY();
        int dz = end.getZ() - start.getZ();

        Vec3 look = player.getLookAngle();
        double ax = Math.abs(look.x);
        double ay = Math.abs(look.y);
        double az = Math.abs(look.z);

        char facingAxis;
        if (ax > ay && ax > az) facingAxis = 'x';
        else if (az > ax && az > ay) facingAxis = 'z';
        else facingAxis = 'y';

        List<Character> axes = new ArrayList<>();
        if (dx != 0) axes.add('x');
        if (dy != 0) axes.add('y');
        if (dz != 0) axes.add('z');

        if (axes.isEmpty()) return path;

        axes.sort((a, b) -> {
            if (a == facingAxis) return 1;
            if (b == facingAxis) return -1;

            int distA = a == 'x' ? Math.abs(dx) : (a == 'y' ? Math.abs(dy) : Math.abs(dz));
            int distB = b == 'x' ? Math.abs(dx) : (b == 'y' ? Math.abs(dy) : Math.abs(dz));
            return Integer.compare(distB, distA);
        });

        for (char axis : axes) {
            int target = switch (axis) {
                case 'x' -> end.getX();
                case 'y' -> end.getY();
                case 'z' -> end.getZ();
                default -> 0;
            };
            moveAlongAxis(path, cursor, target, axis);
        }

        return path;
    }

    private boolean placePath(Level level, ServerPlayer player, BlockPos start, BlockPos end, CompoundTag nbt,
                              ItemStack offhandStack, Direction startDir, Direction endDir) {
        String selectedId = nbt.getString("Selected");
        ResourceLocation resourceId = ResourceLocation.tryParse(selectedId);

        if (resourceId == null) return false;

        Block selectedBlock = ForgeRegistries.BLOCKS.getValue(resourceId);
        if (selectedBlock == null) return false;

        List<BlockPos> path = findPath(start, end, player);

        for (BlockPos pos : path) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.canBeReplaced()) {
                if (level.setBlock(pos, selectedBlock.defaultBlockState(), Block.UPDATE_ALL)) {
                    if (!player.isCreative()) {
                        offhandStack.shrink(1);
                    }
                }
            }
        }

        ((ServerLevel) level).getServer().execute(() -> {
            Direction prevdir = null;
            for (int i = 1; i < path.size(); i++) {
                BlockPos curr = path.get(i);
                BlockPos prev = path.get(i - 1);

                if (level.getBlockEntity(curr) instanceof IPipeNode<?, ?> currnode &&
                        level.getBlockEntity(prev) instanceof IPipeNode<?, ?> prevnode) {
                    Direction dir = getDir(prev, curr);
                    prevnode.setConnection(dir, true, false);
                    currnode.setConnection(dir.getOpposite(), true, false);
                    if (prevnode.canHaveBlockedFaces()) {
                        prevnode.setBlocked(dir, true);
                        if (prevdir != null) prevnode.setBlocked(prevdir.getOpposite(), false);
                    }
                    if (i == 1 && startDir != null) {
                        prevnode.setConnection(startDir, true, false);
                        if (prevnode.canHaveBlockedFaces()) {
                            for (Direction d : Direction.values()) {
                                if (d == dir) continue;
                                prevnode.setBlocked(d, false);
                            }
                        }
                    }
                    if (i == path.size() - 1 && endDir != null) {
                        currnode.setConnection(endDir, true, false);
                        if (currnode.canHaveBlockedFaces()) {
                            currnode.setBlocked(endDir, true);
                            for (Direction d : Direction.values()) {
                                if (d == endDir) continue;
                                currnode.setBlocked(d, false);
                            }
                        }
                    }
                    prevnode.notifyBlockUpdate();
                    currnode.notifyBlockUpdate();
                    prevdir = dir;
                }
            }
        });
        return true;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        CompoundTag nbt = stack.getOrCreateTag();

        if (level.isClientSide()) {
            return InteractionResultHolder.pass(stack);
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.fail(stack);
        }

        if (!(player.getOffhandItem().getItem() instanceof BlockItem blockItem &&
                blockItem.getBlock() instanceof PipeBlock pipeblock)) {
            return InteractionResultHolder.fail(stack);
        }

        if (!nbt.contains("Start")) {
            nbt.put("Start", NbtUtils.writeBlockPos(getTargetPos(player, level, true)));
            Direction startDir = player.isShiftKeyDown() ? null :
                    getDir(getTargetPos(player, level, true), getTargetPos(player, level, false));
            if (startDir != null) nbt.putInt("StartDir", startDir.get3DDataValue());
            nbt.putString("Selected", ForgeRegistries.BLOCKS.getKey(pipeblock).toString());
            return InteractionResultHolder.success(stack);
        }

        BlockPos startPos = NbtUtils.readBlockPos(nbt.getCompound("Start"));
        Direction startDir = nbt.contains("StartDir") ? Direction.from3DDataValue(nbt.getInt("StartDir")) : null;
        BlockPos endPos = getTargetPos(player, level, true);
        Direction endDir = player.isShiftKeyDown() ? null : getDir(endPos, getTargetPos(player, level, false));

        placePath(level, serverPlayer, startPos, endPos, nbt, player.getOffhandItem(), startDir, endDir);
        nbt.remove("Start");
        nbt.remove("Selected");
        nbt.remove("StartDir");
        stack.setTag(nbt);

        return InteractionResultHolder.success(stack);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();

        if (mainHand.getItem() instanceof piperouteritem) {
            if (offHand.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof PipeBlock) {
                event.setCanceled(true);
            }
        }
    }
}

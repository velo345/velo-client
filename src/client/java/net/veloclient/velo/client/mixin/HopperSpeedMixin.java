package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.redstone.HopperSpeedModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hopper Speed (singleplayer): repeats vanilla's own one-item hopper move to move several items
 * per transfer, swaps the 8-tick cooldown, and can switch transfers or item pickup off. See
 * {@link HopperSpeedModule} - never touches a real server, only the integrated one.
 */
//? if <26.1 {
@Mixin(net.minecraft.block.entity.HopperBlockEntity.class)
public abstract class HopperSpeedMixin {

	@Shadow
	private static boolean insert(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos,
			net.minecraft.block.entity.HopperBlockEntity blockEntity) {
		throw new AssertionError();
	}

	@Shadow
	public static boolean extract(net.minecraft.world.World world, net.minecraft.block.entity.Hopper hopper) {
		throw new AssertionError();
	}

	@Inject(method = "insertAndExtract", at = @At("HEAD"), cancellable = true)
	private static void velo$transfersOff(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos, net.minecraft.block.BlockState state,
			net.minecraft.block.entity.HopperBlockEntity blockEntity, java.util.function.BooleanSupplier booleanSupplier,
			CallbackInfoReturnable<Boolean> cir) {
		if (HopperSpeedModule.active && HopperSpeedModule.transfersOff) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "insert", at = @At("HEAD"), cancellable = true)
	private static void velo$pushMany(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos,
			net.minecraft.block.entity.HopperBlockEntity blockEntity, CallbackInfoReturnable<Boolean> cir) {
		if (!HopperSpeedModule.active || HopperSpeedModule.repeating || HopperSpeedModule.itemsPerTransfer <= 1) {
			return;
		}
		HopperSpeedModule.repeating = true;
		try {
			boolean any = false;
			for (int i = 0; i < HopperSpeedModule.itemsPerTransfer && insert(world, pos, blockEntity); i++) {
				any = true;
			}
			cir.setReturnValue(any);
		} finally {
			HopperSpeedModule.repeating = false;
		}
	}

	@Inject(method = "extract(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Z", at = @At("HEAD"), cancellable = true)
	private static void velo$pullMany(net.minecraft.world.World world, net.minecraft.block.entity.Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
		if (!HopperSpeedModule.active || HopperSpeedModule.repeating) {
			return;
		}
		boolean cart = !(hopper instanceof net.minecraft.block.entity.HopperBlockEntity);
		if (cart && !HopperSpeedModule.minecarts) {
			return;
		}
		if (HopperSpeedModule.transfersOff) {
			cir.setReturnValue(false);
			return;
		}
		if (HopperSpeedModule.itemsPerTransfer <= 1) {
			return;
		}
		HopperSpeedModule.repeating = true;
		try {
			boolean any = false;
			for (int i = 0; i < HopperSpeedModule.itemsPerTransfer && extract(world, hopper); i++) {
				any = true;
			}
			cir.setReturnValue(any);
		} finally {
			HopperSpeedModule.repeating = false;
		}
	}

	@Inject(method = "extract(Lnet/minecraft/inventory/Inventory;Lnet/minecraft/entity/ItemEntity;)Z", at = @At("HEAD"), cancellable = true)
	private static void velo$noPickup(net.minecraft.inventory.Inventory inventory, net.minecraft.entity.ItemEntity item, CallbackInfoReturnable<Boolean> cir) {
		if (HopperSpeedModule.active && !HopperSpeedModule.pickUpItems) {
			cir.setReturnValue(false);
		}
	}

	@ModifyConstant(method = {"insertAndExtract",
			"transfer(Lnet/minecraft/inventory/Inventory;Lnet/minecraft/inventory/Inventory;Lnet/minecraft/item/ItemStack;ILnet/minecraft/util/math/Direction;)Lnet/minecraft/item/ItemStack;"},
			constant = @Constant(intValue = 8), require = 0)
	private static int velo$cooldown(int vanilla) {
		return HopperSpeedModule.active ? HopperSpeedModule.cooldown : vanilla;
	}
}
//?} else {
/*@Mixin(net.minecraft.world.level.block.entity.HopperBlockEntity.class)
public abstract class HopperSpeedMixin {

	@Shadow
	private static boolean ejectItems(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos,
			net.minecraft.world.level.block.entity.HopperBlockEntity blockEntity) {
		throw new AssertionError();
	}

	@Shadow
	public static boolean suckInItems(net.minecraft.world.level.Level level, net.minecraft.world.level.block.entity.Hopper hopper) {
		throw new AssertionError();
	}

	@Inject(method = "tryMoveItems", at = @At("HEAD"), cancellable = true)
	private static void velo$transfersOff(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos,
			net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.block.entity.HopperBlockEntity blockEntity,
			java.util.function.BooleanSupplier booleanSupplier, CallbackInfoReturnable<Boolean> cir) {
		if (HopperSpeedModule.active && HopperSpeedModule.transfersOff) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "ejectItems", at = @At("HEAD"), cancellable = true)
	private static void velo$pushMany(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos,
			net.minecraft.world.level.block.entity.HopperBlockEntity blockEntity, CallbackInfoReturnable<Boolean> cir) {
		if (!HopperSpeedModule.active || HopperSpeedModule.repeating || HopperSpeedModule.itemsPerTransfer <= 1) {
			return;
		}
		HopperSpeedModule.repeating = true;
		try {
			boolean any = false;
			for (int i = 0; i < HopperSpeedModule.itemsPerTransfer && ejectItems(level, pos, blockEntity); i++) {
				any = true;
			}
			cir.setReturnValue(any);
		} finally {
			HopperSpeedModule.repeating = false;
		}
	}

	@Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
	private static void velo$pullMany(net.minecraft.world.level.Level level, net.minecraft.world.level.block.entity.Hopper hopper,
			CallbackInfoReturnable<Boolean> cir) {
		if (!HopperSpeedModule.active || HopperSpeedModule.repeating) {
			return;
		}
		boolean cart = !(hopper instanceof net.minecraft.world.level.block.entity.HopperBlockEntity);
		if (cart && !HopperSpeedModule.minecarts) {
			return;
		}
		if (HopperSpeedModule.transfersOff) {
			cir.setReturnValue(false);
			return;
		}
		if (HopperSpeedModule.itemsPerTransfer <= 1) {
			return;
		}
		HopperSpeedModule.repeating = true;
		try {
			boolean any = false;
			for (int i = 0; i < HopperSpeedModule.itemsPerTransfer && suckInItems(level, hopper); i++) {
				any = true;
			}
			cir.setReturnValue(any);
		} finally {
			HopperSpeedModule.repeating = false;
		}
	}

	@Inject(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z", at = @At("HEAD"), cancellable = true)
	private static void velo$noPickup(net.minecraft.world.Container container, net.minecraft.world.entity.item.ItemEntity item,
			CallbackInfoReturnable<Boolean> cir) {
		if (HopperSpeedModule.active && !HopperSpeedModule.pickUpItems) {
			cir.setReturnValue(false);
		}
	}

	@ModifyConstant(method = {"tryMoveItems",
			"tryMoveInItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;"},
			constant = @Constant(intValue = 8), require = 0)
	private static int velo$cooldown(int vanilla) {
		return HopperSpeedModule.active ? HopperSpeedModule.cooldown : vanilla;
	}
}
*///?}

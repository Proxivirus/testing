package com.proxi.whistle.util;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

import java.lang.reflect.Method;

/**
 * Small compatibility shim for ItemStack NBT accessor names across Yarn / MCP mapping differences.
 * Tries several method names in order until one works:
 *  - getNbt()
 *  - getTag()
 *  - getOrCreateNbt()
 *  - getOrCreateTag()
 *
 * For setters it tries setNbt(NbtCompound) and setTag(NbtCompound).
 *
 * This lets the rest of the mod call a single API without caring about the exact mappings.
 */
public final class ItemStackNbtUtil {
    private ItemStackNbtUtil() {}

    private static Method getter = null;
    private static Method getterOrCreate = null;
    private static Method setter = null;

    static {
        // Try getters
        String[] getters = new String[] { "getNbt", "getTag", "getOrCreateNbt", "getOrCreateTag" };
        for (String name : getters) {
            try {
                Method m = ItemStack.class.getMethod(name);
                getter = m;
                // if this is an "or create" variant return as getterOrCreate too
                if (name.toLowerCase().contains("orcreate")) getterOrCreate = m;
                // stop on first found
                break;
            } catch (NoSuchMethodException ignored) {}
        }

        // If we didn't find a plain getter, try to find a plain "getNbt" that returns null (some mappings)
        if (getter == null) {
            try {
                Method m = ItemStack.class.getMethod("getNbt");
                getter = m;
            } catch (NoSuchMethodException ignored) {}
        }

        // Try explicit getOrCreate method names
        if (getterOrCreate == null) {
            String[] orCreateNames = new String[] { "getOrCreateNbt", "getOrCreateTag" };
            for (String name : orCreateNames) {
                try {
                    Method m = ItemStack.class.getMethod(name);
                    getterOrCreate = m;
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
        }

        // Try setters
        String[] setters = new String[] { "setNbt", "setTag" };
        for (String name : setters) {
            try {
                Method m = ItemStack.class.getMethod(name, NbtCompound.class);
                setter = m;
                break;
            } catch (NoSuchMethodException ignored) {}
        }
    }

    /**
     * Returns the NbtCompound on the ItemStack or null if none exists.
     */
    public static NbtCompound getNbt(ItemStack stack) {
        if (stack == null) return null;
        try {
            if (getter != null) {
                Object res = getter.invoke(stack);
                if (res instanceof NbtCompound nc) return nc;
            }
        } catch (Throwable ignored) {}
        // last resort: try invoking a known name manually and catch errors
        try {
            Method m = ItemStack.class.getMethod("getTag");
            Object res = m.invoke(stack);
            if (res instanceof NbtCompound nc) return nc;
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Returns the existing NbtCompound or creates, sets and returns a fresh one.
     */
    public static NbtCompound getOrCreateNbt(ItemStack stack) {
        if (stack == null) return null;
        try {
            // If an explicit "or create" method exists, call it (it returns a non-null compound)
            if (getterOrCreate != null) {
                Object res = getterOrCreate.invoke(stack);
                if (res instanceof NbtCompound nc) return nc;
            }

            // Otherwise attempt to get existing NBT
            NbtCompound cur = getNbt(stack);
            if (cur != null) return cur;

            // create new compound and set via setter
            NbtCompound fresh = new NbtCompound();
            if (setter != null) {
                setter.invoke(stack, fresh);
                return fresh;
            }

            // fallback: try setTag reflection name
            try {
                Method setTag = ItemStack.class.getMethod("setTag", NbtCompound.class);
                setTag.invoke(stack, fresh);
                return fresh;
            } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}

        // if all reflection failed, return a new compound but it won't persist to the ItemStack
        return new NbtCompound();
    }

    /**
     * Convenience: set the ItemStack's NBT compound (best-effort).
     */
    public static void setNbt(ItemStack stack, NbtCompound nbt) {
        if (stack == null) return;
        try {
            if (setter != null) {
                setter.invoke(stack, nbt);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            Method setTag = ItemStack.class.getMethod("setTag", NbtCompound.class);
            setTag.invoke(stack, nbt);
        } catch (Throwable ignored) {}
    }
}

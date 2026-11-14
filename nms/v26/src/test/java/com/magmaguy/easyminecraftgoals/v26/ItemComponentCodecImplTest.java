package com.magmaguy.easyminecraftgoals.v26;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumables;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unchecked")
class ItemComponentCodecImplTest {

    private static RegistryAccess registries;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        // Item prototypes are bound when a server loads its registries; bind the two this test
        // uses by hand, with the components the assertions rely on.
        bind(Items.NETHER_BRICK, DataComponentMap.builder()
                .set(DataComponents.MAX_STACK_SIZE, 64)
                .build());
        bind(Items.APPLE, DataComponentMap.builder()
                .set(DataComponents.MAX_STACK_SIZE, 64)
                .set(DataComponents.FOOD, new FoodProperties(4, 2.4F, false))
                .set(DataComponents.CONSUMABLE, Consumables.DEFAULT_FOOD)
                .build());
    }

    private static void bind(Item item, DataComponentMap prototype) {
        Holder.Reference<Item> holder = (Holder.Reference<Item>) BuiltInRegistries.ITEM.wrapAsHolder(item);
        if (!holder.areComponentsBound()) holder.bindComponents(prototype);
    }

    @Test
    void patchHoldsOnlyWhatTheStackChanges() {
        ItemStack stack = new ItemStack(Items.NETHER_BRICK);
        stack.set(DataComponents.MAX_STACK_SIZE, 16);
        stack.set(DataComponents.FOOD, new FoodProperties(4, 2.4F, true));

        JsonObject patch = parse(ItemComponentCodecImpl.encode(stack, registries, true));

        assertEquals(16, patch.get("minecraft:max_stack_size").getAsInt());
        JsonObject food = patch.getAsJsonObject("minecraft:food");
        assertEquals(4, food.get("nutrition").getAsInt());
        assertEquals(2.4F, food.get("saturation").getAsFloat(), 1e-6);
        assertTrue(food.get("can_always_eat").getAsBoolean());
        assertEquals(2, patch.size());
    }

    @Test
    void removedPrototypeComponentsAreMarked() {
        ItemStack apple = new ItemStack(Items.APPLE);
        apple.remove(DataComponents.FOOD);

        JsonObject patch = parse(ItemComponentCodecImpl.encode(apple, registries, true));

        assertTrue(patch.has("!minecraft:food"));
        assertEquals(new JsonObject(), patch.getAsJsonObject("!minecraft:food"));
    }

    @Test
    void fullMapIncludesPrototypeComponents() {
        JsonObject all = parse(ItemComponentCodecImpl.encode(new ItemStack(Items.APPLE), registries, false));

        assertTrue(all.has("minecraft:food"));
        assertTrue(all.has("minecraft:consumable"));
        assertEquals(64, all.get("minecraft:max_stack_size").getAsInt());
        // The codec leaves out fields at their default, as the vanilla consume time is.
        assertTrue(all.getAsJsonObject("minecraft:consumable").keySet().isEmpty());
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}

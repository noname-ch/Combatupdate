package ch.bbcag.combatupdate.client;

import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.LayerDefinitions;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.enchantment.BatteringRam;

// Swaps a helmet's worn model for one with its top shaved flat whenever it carries the Battering Ram
// enchantment - a permanent dent from taking every impact meant for its wearer's head instead.
// Registered for every vanilla helmet in CombatUpdateClient.
//
// A helmet only ever shows its "head" part (the rest of a HumanoidModel's parts are hidden for that
// slot), so the replacement only needs that one part built; everything else is pruned away exactly the
// way vanilla prunes its own per-slot armor models.
@OnlyIn(Dist.CLIENT)
public final class BatteringRamHelmetModel implements IClientItemExtensions {
    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "battering_ram_helmet"), "main");

    // How tall the flattened head box is, against vanilla's 8-pixel head: 2 pixels come off the top,
    // and the bottom stays put at the neck so the helmet still sits where it should.
    private static final float FLATTENED_HEIGHT = 6.0F;

    public static final BatteringRamHelmetModel INSTANCE = new BatteringRamHelmetModel();

    private static HumanoidModel<HumanoidRenderState> flattenedModel;

    private BatteringRamHelmetModel() {
    }

    public static LayerDefinition buildLayerDefinition() {
        // Vanilla's own head+body+limbs mesh, so the replacement always matches whatever HumanoidModel
        // currently builds instead of a hand-copied set of numbers that could quietly drift out of sync.
        MeshDefinition mesh = HumanoidModel.createMesh(LayerDefinitions.OUTER_ARMOR_DEFORMATION, 0.0F);
        PartDefinition root = mesh.getRoot();

        // Same box as vanilla's head, just shorter: the bottom stays at the neck and the top comes
        // down to meet it, rather than scaling in from the centre.
        root.addOrReplaceChild("head",
                CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-4.0F, -FLATTENED_HEIGHT, -4.0F, 8.0F, FLATTENED_HEIGHT, 8.0F, LayerDefinitions.OUTER_ARMOR_DEFORMATION),
                PartPose.ZERO);

        root.retainPartsAndChildren(Set.of("head"));
        return LayerDefinition.create(mesh, 64, 32);
    }

    private static HumanoidModel<HumanoidRenderState> flattenedModel() {
        if (flattenedModel == null) {
            ModelPart root = Minecraft.getInstance().getEntityModels().bakeLayer(LAYER);
            flattenedModel = new HumanoidModel<>(root);
        }

        return flattenedModel;
    }

    @Override
    public Model getHumanoidArmorModel(ItemStack itemStack, EquipmentClientInfo.LayerType layerType, Model original) {
        if (!(original instanceof HumanoidModel<?>)) {
            return original;
        }

        Level level = Minecraft.getInstance().level;
        if (level == null || BatteringRam.ramLevel(itemStack, level) <= 0) {
            return original;
        }

        return flattenedModel();
    }
}

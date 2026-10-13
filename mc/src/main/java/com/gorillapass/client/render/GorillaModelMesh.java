package com.gorillapass.client.render;

import java.util.Set;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.ArmorModelSet;

/**
 * Our own blocky gorilla, built with the player model's part names, so everything that poses or
 * dresses a player (walking, swinging, armor, held items) works on it unchanged.
 * <p>
 * Model units are pixels with y pointing down; the ground is at y = 24. The gorilla is about 14 pixels
 * (0.8 blocks) tall: a big head, a short wide body, long arms with fists, and no legs.
 */
public final class GorillaModelMesh {
	/** Light grey fur with a darker face and palms; it's tinted with each player's Gorilla Tag fur colour. */
	public static final net.minecraft.resources.Identifier FUR_TEXTURE = com.gorillapass.GorillaNet.id("textures/entity/gorilla.png");
	/** Another gorilla's hands older than this (its Gorilla Tag stopped) aren't used. */
	public static final long HANDS_FRESH_MILLIS = 1000L;

	/** Neck: where the head sits on the body. */
	public static final float NECK_Y = 18.0F;
	public static final float SHOULDER_X = 6.0F;
	public static final float SHOULDER_Y = 19.0F;
	/** Arm cubes run from 1.5 above the shoulder to the end of the fist at 11.5 below it. */
	public static final float ARM_END_Y = 11.5F;
	/** Middle of the fist, below the shoulder. */
	public static final float FIST_CENTER_Y = 10.0F;

	private GorillaModelMesh() {
	}

	public static MeshDefinition createBodyMesh(CubeDeformation deformation) {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();

		PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create()
				.texOffs(0, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 7.0F, deformation)
				// heavy brow over deep-set eyes
				.texOffs(32, 0).addBox(-4.0F, -7.0F, -5.5F, 8.0F, 2.0F, 2.0F, deformation)
				// muzzle
				.texOffs(32, 4).addBox(-3.0F, -4.0F, -6.0F, 6.0F, 3.0F, 3.0F, deformation)
				// small ears
				.texOffs(52, 0).addBox(-5.0F, -6.0F, -1.0F, 1.0F, 2.0F, 1.0F, deformation)
				.texOffs(56, 0).addBox(4.0F, -6.0F, -1.0F, 1.0F, 2.0F, 1.0F, deformation),
			PartPose.offset(0.0F, NECK_Y, -0.5F));
		head.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);

		PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create()
				.texOffs(0, 16).addBox(-5.0F, -1.0F, -3.0F, 10.0F, 7.0F, 6.0F, deformation)
				// the silverback's muscly shoulder hump
				.texOffs(0, 32).addBox(-4.0F, -2.5F, -1.0F, 8.0F, 3.0F, 5.0F, deformation),
			PartPose.offset(0.0F, NECK_Y, 0.0F));
		body.addOrReplaceChild("jacket", CubeListBuilder.create(), PartPose.ZERO);

		PartDefinition rightArm = root.addOrReplaceChild("right_arm", arm(32, 16, 0, 44, deformation),
			PartPose.offset(-SHOULDER_X, SHOULDER_Y, 0.0F));
		rightArm.addOrReplaceChild("right_sleeve", CubeListBuilder.create(), PartPose.ZERO);

		PartDefinition leftArm = root.addOrReplaceChild("left_arm", arm(48, 16, 20, 44, deformation),
			PartPose.offset(SHOULDER_X, SHOULDER_Y, 0.0F));
		leftArm.addOrReplaceChild("left_sleeve", CubeListBuilder.create(), PartPose.ZERO);

		// no legs: the parts exist (the player model needs them) but are empty
		PartDefinition rightLeg = root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-2.0F, 24.0F, 0.0F));
		rightLeg.addOrReplaceChild("right_pants", CubeListBuilder.create(), PartPose.ZERO);
		PartDefinition leftLeg = root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(2.0F, 24.0F, 0.0F));
		leftLeg.addOrReplaceChild("left_pants", CubeListBuilder.create(), PartPose.ZERO);

		return mesh;
	}

	private static CubeListBuilder arm(int armU, int armV, int fistU, int fistV, CubeDeformation deformation) {
		return CubeListBuilder.create()
			.texOffs(armU, armV).addBox(-2.0F, -1.5F, -2.0F, 4.0F, 10.0F, 4.0F, deformation)
			.texOffs(fistU, fistV).addBox(-2.5F, 8.5F, -2.5F, 5.0F, 3.0F, 5.0F, deformation);
	}

	/**
	 * Armor uses vanilla's armor textures and cube layout, moved onto the gorilla's head, body and arms.
	 * The chestplate is cut short and widened to wrap the gorilla's stubby body.
	 */
	public static ArmorModelSet<MeshDefinition> createArmorMeshSet(CubeDeformation inner, CubeDeformation outer) {
		MeshDefinition head = createArmorBase(outer);
		head.getRoot().retainPartsAndChildren(Set.of("head"));
		MeshDefinition chest = createArmorBase(outer);
		chest.getRoot().retainExactParts(Set.of("body", "left_arm", "right_arm"));
		MeshDefinition legs = createArmorBase(inner);
		legs.getRoot().retainExactParts(Set.of("left_leg", "right_leg", "body"));
		MeshDefinition feet = createArmorBase(outer);
		feet.getRoot().retainExactParts(Set.of("left_leg", "right_leg"));

		return new ArmorModelSet<>(head, chest, legs, feet).map(mesh -> {
			PartDefinition root = mesh.getRoot();
			root.getChild("left_arm").addOrReplaceChild("left_sleeve", CubeListBuilder.create(), PartPose.ZERO);
			root.getChild("right_arm").addOrReplaceChild("right_sleeve", CubeListBuilder.create(), PartPose.ZERO);
			root.getChild("left_leg").addOrReplaceChild("left_pants", CubeListBuilder.create(), PartPose.ZERO);
			root.getChild("right_leg").addOrReplaceChild("right_pants", CubeListBuilder.create(), PartPose.ZERO);
			root.getChild("body").addOrReplaceChild("jacket", CubeListBuilder.create(), PartPose.ZERO);
			return mesh;
		});
	}

	private static MeshDefinition createArmorBase(CubeDeformation g) {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create()
				.texOffs(0, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, g),
			PartPose.offset(0.0F, NECK_Y, -0.5F));
		head.addOrReplaceChild("hat", CubeListBuilder.create()
			.texOffs(32, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, g.extend(0.5F)), PartPose.ZERO);
		root.addOrReplaceChild("body", CubeListBuilder.create()
				.texOffs(16, 16).addBox(-4.0F, -1.0F, -2.0F, 8.0F, 8.0F, 4.0F, g.extend(0.75F, 0.0F, 1.5F)),
			PartPose.offset(0.0F, NECK_Y, 0.0F));
		root.addOrReplaceChild("right_arm", CubeListBuilder.create()
				.texOffs(40, 16).addBox(-2.0F, -1.5F, -2.0F, 4.0F, 10.0F, 4.0F, g),
			PartPose.offset(-SHOULDER_X, SHOULDER_Y, 0.0F));
		root.addOrReplaceChild("left_arm", CubeListBuilder.create()
				.texOffs(40, 16).mirror().addBox(-2.0F, -1.5F, -2.0F, 4.0F, 10.0F, 4.0F, g),
			PartPose.offset(SHOULDER_X, SHOULDER_Y, 0.0F));
		root.addOrReplaceChild("right_leg", CubeListBuilder.create()
				.texOffs(0, 16).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, g.extend(-0.1F)),
			PartPose.offset(-2.0F, 24.0F, 0.0F));
		root.addOrReplaceChild("left_leg", CubeListBuilder.create()
				.texOffs(0, 16).mirror().addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, g.extend(-0.1F)),
			PartPose.offset(2.0F, 24.0F, 0.0F));
		return mesh;
	}
}

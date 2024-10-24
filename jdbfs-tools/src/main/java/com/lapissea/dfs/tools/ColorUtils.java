package com.lapissea.dfs.tools;

import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.util.MathUtil;

import java.awt.Color;
import java.util.random.RandomGenerator;

public final class ColorUtils{
	
	public static Color mul(Color color, float mul){
		return new Color(Math.round(color.getRed()*mul), Math.round(color.getGreen()*mul), Math.round(color.getBlue()*mul), color.getAlpha());
	}
	
	public static Color add(Color color, Color other){
		return new Color(
			Math.min(255, color.getRed() + other.getRed()),
			Math.min(255, color.getGreen() + other.getGreen()),
			Math.min(255, color.getBlue() + other.getBlue()),
			Math.min(255, color.getAlpha() + other.getAlpha())
		);
	}
	
	public static Color alpha(Color color, float alpha){
		return new Color(
			color.getRed(),
			color.getGreen(),
			color.getBlue(),
			(int)(alpha*255)
		);
	}
	
	public static Color mix(Color color, Color other, float mul){
		return add(mul(color, 1 - mul), mul(other, mul));
	}
	
	public static Color makeCol(int typeHash, IOField<?, ?> field){
		return makeCol(typeHash, field.getName());
	}
	public static Color makeCol(int typeHash, String fieldName){
		float typeHue   = calcHue(new RawRandom(typeHash));
		var   fieldRand = new RawRandom(fieldName.hashCode());
		float fieldHue  = calcHue(fieldRand);
		
		float mix = 0.4F;
//		var   hue=typeHue;
		var hue = typeHue*mix + fieldHue*(1 - mix);
		
		float brightness = 1;
		float saturation = calcSaturation(fieldRand);
		
		return new Color(Color.HSBtoRGB(hue, saturation, brightness));
	}
	
	private static float calcSaturation(RandomGenerator rand){
		float saturation;
//		saturation=0.8F;
		saturation = rand.nextFloat()*0.4F + 0.6F;
		return saturation;
	}
	
	private static float calcHue(RandomGenerator rand){
		float[] hues = {
			0.1F,
			1,
			2
		};
		
		float hueStep = hues[rand.nextInt(hues.length)]/3F;
		
		float hueOffset = MathUtil.sq(rand.nextFloat());
		if(rand.nextBoolean()) hueOffset *= -1;
		hueOffset /= 600;
//		LogUtil.printTable("col", hueStep, "off", hueOffset);
		return hueStep + hueOffset;
	}
}

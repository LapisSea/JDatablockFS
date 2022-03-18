package com.lapissea.cfs.tools;

import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.MathUtil;

import java.awt.Color;
import java.util.Random;

public class ColorUtils{
	
	public static Color mul(Color color, float mul){
		return new Color(Math.round(color.getRed()*mul), Math.round(color.getGreen()*mul), Math.round(color.getBlue()*mul), color.getAlpha());
	}
	
	public static Color add(Color color, Color other){
		return new Color(
			Math.min(255, color.getRed()+other.getRed()),
			Math.min(255, color.getGreen()+other.getGreen()),
			Math.min(255, color.getBlue()+other.getBlue()),
			Math.min(255, color.getAlpha()+other.getAlpha())
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
		return add(mul(color, 1-mul), mul(other, mul));
	}
	
	
	public static Color makeCol(Random rand, int typeHash, IOField<?, ?> field){
		return makeCol(rand, typeHash, field.getName());
	}
	
	public static Color makeCol(Random rand, int typeHash, String fieldName){
		
		rand.setSeed(typeHash);
		float typeHue=calcHue(rand);
		
		rand.setSeed(fieldName.hashCode());
		float fieldHue=calcHue(rand);
		
		float mix=0.4F;
//		var   hue=typeHue;
		var hue=typeHue*mix+fieldHue*(1-mix);
		
		float brightness=1;
		float saturation=calcSaturation(rand);
		
		return new Color(Color.HSBtoRGB(hue, saturation, brightness));
	}
	
	private static float calcSaturation(Random rand){
		float saturation;
//		saturation=0.8F;
		saturation=rand.nextFloat()*0.4F+0.6F;
		return saturation;
	}
	
	private static float calcHue(Random rand){
		float[] hues={
			0.1F,
			1,
			2
		};
		
		float hueStep=hues[rand.nextInt(hues.length)]/3F;
		
		float hueOffset=MathUtil.sq(rand.nextFloat());
		if(rand.nextBoolean()) hueOffset*=-1;
		hueOffset/=600;
//		LogUtil.printTable("col", hueStep, "off", hueOffset);
		var hue=hueStep+hueOffset;
		return hue;
	}
}

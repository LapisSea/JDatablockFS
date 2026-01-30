package com.lapissea.dfs.inspect.display;

import java.awt.Color;
import java.util.random.RandomGenerator;

public record Col(float r, float g, float b, float a){
	
	public static final Col WHITE      = new Col(1, 1, 1);
	public static final Col BLACK      = new Col(0, 0, 0);
	public static final Col RED        = new Col(1, 0, 0);
	public static final Col GREEN      = new Col(0, 1, 0);
	public static final Col BLUE       = new Col(0, 0, 1);
	public static final Col CYAN       = new Col(0, 1, 1);
	public static final Col ORANGE     = new Col(1, 200/255F, 0);
	public static final Col LIGHT_GRAY = new Col(192/255F, 192/255F, 192/255F);
	public static final Col DARK_GRAY  = new Col(64/255F, 64/255F, 64/255F);
	
	private static float segment(int col, int off){
		return ((col>>off)&0xFF)/255F;
	}
	
	public static Col rgba(int rgba){
		return new Col(segment(rgba, 0), segment(rgba, 8), segment(rgba, 16), segment(rgba, 24));
	}
	
	public Col(Color color){
		this(color.getRed()/255F, color.getGreen()/255F, color.getBlue()/255F, color.getAlpha()/255F);
	}
	public Col(RandomGenerator r){
		this(r.nextFloat(), r.nextFloat(), r.nextFloat());
	}
	public Col(float r, float g, float b){
		this(r, g, b, 1);
	}
	
	public Col mul(Col other){
		return new Col(
			r*other.r,
			g*other.g,
			b*other.b,
			a*other.a
		);
	}
	public Col mul(float factor){
		return new Col(
			r*factor,
			g*factor,
			b*factor,
			a
		);
	}
	public Col a(float a){
		return new Col(r, g, b, a);
	}
	
	public Col mix(Col other, float factor){
		if(factor<=0) return this;
		if(factor>=1) return other;
		var f2 = 1 - factor;
		return new Col(
			r*f2 + other.r*factor,
			g*f2 + other.g*factor,
			b*f2 + other.b*factor,
			a*f2 + other.a*factor
		);
	}
	
	private static final float FACTOR = 0.7F;
	public Col brighter(){
		float r = this.r;
		float g = this.g;
		float b = this.b;
		float a = this.a;
		
		int i = (int)(1.0/(1.0 - FACTOR));
		if(r<=0.0001F && g<=0.0001F && b<=0.0001F){
			return new Col(i, i, i, a);
		}
		if(r>0 && r<i) r = i;
		if(g>0 && g<i) g = i;
		if(b>0 && b<i) b = i;
		
		return new Col(r/FACTOR, g/FACTOR, b/FACTOR, a);
	}
	public Col darker(){
		return mul(FACTOR);
	}
	
	private static int snapInt(float v){
		var snapped = Math.min(Math.max(v, 0), 1);
		return Math.round(snapped*255);
	}
	
	public int toRGBAi4(){
		int rB = snapInt(r);
		int gB = snapInt(g);
		int bB = snapInt(b);
		int aB = snapInt(a);
		return rB|(gB<<8)|(bB<<16)|(aB<<24);
	}
	@Override
	public int hashCode(){
		return Float.floatToRawIntBits(r + g + b + a);
	}
	@Override
	public boolean equals(Object obj){
		return obj instanceof Col(float r1, float g1, float b1, float a1) &&
		       r == r1 && g == g1 && b == b1 && a == a1;
	}
}

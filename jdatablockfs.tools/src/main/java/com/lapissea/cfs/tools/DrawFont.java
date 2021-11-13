package com.lapissea.cfs.tools;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;

public abstract class DrawFont{
	public static record Bounds(float width, float height){}
	
	public static record StringDraw(float pixelHeight, Color color, String string, float x, float y){}
	
	public void fillStrings(StringDraw... strings){
		fillStrings(Arrays.asList(strings));
	}
	public void outlineStrings(StringDraw... strings){
		outlineStrings(Arrays.asList(strings));
	}
	
	public abstract void fillStrings(List<StringDraw> strings);
	public abstract void outlineStrings(List<StringDraw> strings);
	
	public abstract Bounds getStringBounds(String string);
	
	public boolean canFontDisplay(int c){
		if(c==0) return false;
		return canFontDisplay(c);
	}
	public abstract boolean canFontDisplay(char c);
}

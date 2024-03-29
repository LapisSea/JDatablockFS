package com.lapissea.dfs.tools;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;

public abstract class DrawFont{
	public record Bounds(float width, float height){ }
	
	public record StringDraw(float pixelHeight, float xScale, Color color, String string, float x, float y){
		public StringDraw(float pixelHeight, Color color, String string, float x, float y){
			this(pixelHeight, 1, color, string, x, y);
		}
	}
	
	public void fillStrings(StringDraw... strings){
		fillStrings(Arrays.asList(strings));
	}
	public void outlineStrings(StringDraw... strings){
		outlineStrings(Arrays.asList(strings));
	}
	
	public abstract void fillStrings(List<StringDraw> strings);
	public abstract void outlineStrings(List<StringDraw> strings);
	
	public abstract Bounds getStringBounds(String string, float fontScale);
	
	public boolean canFontDisplay(byte c){
		if(c == 0) return false;
		return canFontDisplay((char)(c&0xFF));
	}
	public abstract boolean canFontDisplay(char c);
}

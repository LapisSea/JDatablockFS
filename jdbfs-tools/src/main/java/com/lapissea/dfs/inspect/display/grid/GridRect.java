package com.lapissea.dfs.inspect.display.grid;

import java.util.Comparator;

public record GridRect(float x, float y, float width, float height){
	
	public static final Comparator<GridRect> AREA_COMPARATOR = (a, b) -> Float.compare(a.area(), b.area());
	
	public GridRect(float width, float height){
		this(0, 0, width, height);
	}
	public GridRect scale(float scale){
		return new GridRect(x*scale, y*scale, width*scale, height*scale);
	}
	
	public float area(){ return width*height; }
}

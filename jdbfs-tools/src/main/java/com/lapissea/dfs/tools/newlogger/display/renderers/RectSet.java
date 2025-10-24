package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.DrawUtils.Rect;

import java.util.ArrayList;
import java.util.List;

public class RectSet{
	
	private final List<Rect> rects = new ArrayList<>();
	
	public void add(Rect rect){
		rects.add(rect);
	}
	
	public boolean overlaps(Rect rect){
		for(Rect r : rects){
			if(r.overlaps(rect)){
				return true;
			}
		}
		return false;
	}
	
	public List<Rect> all(){
		return rects;
	}
}

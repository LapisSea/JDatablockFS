package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.Utils;
import org.joml.Vector2f;

import java.awt.Color;

public class VertexBuilder{
	
	private float[] xy;
	private int[]   color;
	private int     cursor;
	
	public VertexBuilder(){
		this(4);
	}
	public VertexBuilder(int initialCapacity){
		this.xy = new float[initialCapacity*2];
		this.color = new int[initialCapacity];
	}
	
	public void add(Vector2f xy, Color color){
		add(xy.x, xy.y, VUtils.toRGBAi4(color));
	}
	public void add(Vector2f xy, int color){
		add(xy.x, xy.y, color);
	}
	public void add(float x, float y, Color color){
		add(x, y, VUtils.toRGBAi4(color));
	}
	public void add(float x, float y, int color){
		if(cursor == this.color.length){
			xy = Utils.growArr(xy);
			this.color = Utils.growArr(this.color);
		}
		var c2 = cursor*2;
		xy[c2] = x;
		xy[c2 + 1] = y;
		this.color[cursor] = color;
		cursor++;
	}
	
	public Vector2f getPos(int index){
		return new Vector2f(xy[index*2], xy[index*2 + 1]);
	}
	public int size(){
		return cursor;
	}
	public float[] getXy(){
		return xy;
	}
	public int[] getColor(){
		return color;
	}
}

package com.lapissea.dfs.inspect.display;

import com.lapissea.dfs.Utils;
import org.joml.Vector2f;

import java.awt.Color;
import java.util.Arrays;

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
	public void add(VertexBuilder nVerts){
		var newSize = cursor + nVerts.size();
		if(newSize>=this.color.length){
			var growSize = Math.max(this.color.length*2, newSize);
			xy = Arrays.copyOf(xy, growSize*2);
			color = Arrays.copyOf(color, growSize);
		}
		System.arraycopy(nVerts.xy, 0, this.xy, cursor*2, nVerts.size()*2);
		System.arraycopy(nVerts.color, 0, this.color, cursor, nVerts.size());
		cursor = newSize;
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

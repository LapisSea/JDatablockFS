package com.lapissea.dfs.inspect.display.renderers;

import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.List;

public class BezierCurveRes{
	
	private final List<Vector2f> controlPoints;
	private final int            n;
	private final float[][]      binomial;
	
	public BezierCurveRes(List<Vector2f> controlPoints){
		if(controlPoints == null || controlPoints.size()<2){
			throw new IllegalArgumentException("Need at least 2 control points");
		}
		this.controlPoints = new ArrayList<>(controlPoints);
		this.n = controlPoints.size();
		this.binomial = computeBinomialCoefficients(n - 1);
	}
	
	public Vector2f eval(float t, Vector2f dest){
		if(dest == null) dest = new Vector2f();
		float u = 1 - t;
		dest.set(0, 0);
		
		for(int i = 0; i<n; i++){
			float b = binomial[n - 1][i]*(float)Math.pow(u, n - 1 - i)*(float)Math.pow(t, i);
			dest.x += b*controlPoints.get(i).x;
			dest.y += b*controlPoints.get(i).y;
		}
		
		return dest;
	}
	
	public List<Vector2f> sample(int samples){
		if(samples<2) samples = 2;
		List<Vector2f> pts = new ArrayList<>(samples);
		Vector2f       tmp = new Vector2f();
		for(int i = 0; i<samples; i++){
			float t = i/(float)(samples - 1);
			pts.add(eval(t, new Vector2f(tmp)));
		}
		return pts;
	}
	
	private static float[][] computeBinomialCoefficients(int n){
		float[][] c = new float[n + 1][n + 1];
		for(int i = 0; i<=n; i++){
			c[i][0] = c[i][i] = 1f;
			for(int j = 1; j<i; j++){
				c[i][j] = c[i - 1][j - 1] + c[i - 1][j];
			}
		}
		return c;
	}
	
}

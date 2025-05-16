package com.lapissea.dfs.tools.newlogger.display.renderers;

import org.joml.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class Geometry{
	
	
	public record Line(List<Vector2f> points, float width, Color color){ }
	
	record Vertex(Vector2f pos, Color color){ }
	
	public record IndexedMesh(Vertex[] verts, int[] indices){ }
	
	public record MeshSize(int vertCount, int indexCount){ }
	static MeshSize calculateMeshSize(Line line){
		int pointCount = line.points().size();
		if(pointCount<2) return new MeshSize(0, 0);
		return new MeshSize(2*pointCount, 6*(pointCount - 1));
	}
	static MeshSize calculateMeshSize(Iterable<Line> lines){
		int vertCount = 0, indexCount = 0;
		for(Line line : lines){
			var s = calculateMeshSize(line);
			vertCount += s.vertCount;
			indexCount += s.indexCount;
		}
		return new MeshSize(vertCount, indexCount);
	}
	static IndexedMesh generateThickLineMesh(Line line){
		
		var      size    = calculateMeshSize(line);
		Vertex[] verts   = new Vertex[size.vertCount];
		int[]    indices = new int[size.indexCount];
		
		var pts = line.points();
		if(pts.size()<2) return new IndexedMesh(verts, indices);
		
		int vertPos = 0;
		int idxPos  = 0;
		
		float halfWidth = line.width()*0.5f;
		Color color     = line.color();
		
		for(int i = 0; i<pts.size(); i++){
			var current = pts.get(i);
			
			var dir = new Vector2f();
			
			if(i == 0){
				var next = pts.get(i + 1);
				current.sub(next, dir);
			}else if(i<pts.size() - 1){
				var prev = pts.get(i - 1);
				var next = pts.get(i + 1);
				prev.sub(current, dir);
				dir.add(current.sub(next, new Vector2f()));
			}else{
				var prev = pts.get(i - 1);
				prev.sub(current, dir);
			}
			var angle = -Math.atan2(dir.y, dir.x);
			
			var mx = new Vector2f((float)Math.sin(angle), (float)Math.cos(angle)).mul(halfWidth);
			
			verts[vertPos++] = new Vertex(current.add(mx, new Vector2f()), color);
			verts[vertPos++] = new Vertex(current.sub(mx, new Vector2f()), color);
		}
		
		int[] quad1 = {0, 1, 2,
		               1, 3, 2};
		int[] quad2 = {0, 1, 3,
		               0, 3, 2};
		
		
		for(int idx = 0; idx<size.vertCount - 2; idx += 2){
			int i0 = idx, i1 = idx + 1, i2 = idx + 2, i3 = idx + 3;
			
			var len1 = verts[i0].pos.distance(verts[i3].pos);
			var len2 = verts[i1].pos.distance(verts[i2].pos);
			
			var quad = len1>len2? quad1 : quad2;
			for(int i = 0; i<6; i++){
				indices[idxPos++] = idx + quad[i];
			}
		}
		assert vertPos == verts.length;
		assert idxPos == indices.length;
		
		return new IndexedMesh(verts, indices);
	}
	
	
	private record DistMax(int index, double maxDist){ }
	
	public static List<Vector2f> douglasPeucker(List<Vector2f> points, double epsilon){
		if(points.size()<3){
			return points;
		}
		
		int    index   = -1;
		double maxDist = 0;
		
		Vector2f lineStart = points.getFirst(), lineEnd = points.getLast();
		
		double dx = lineEnd.x - lineStart.x, dy = lineEnd.y - lineStart.y;
		
		if(dx == 0 && dy == 0){
			var el = computeZeroLine(points, lineStart);
			maxDist = el.maxDist;
			index = el.index;
		}else{
			var    lConstant   = lineEnd.x*lineStart.y - lineEnd.y*lineStart.x;
			double denominator = Math.hypot(dx, dy);
			for(int i = 1, s = points.size() - 1; i<s; i++){
				Vector2f p = points.get(i);
				
				double numerator = Math.abs(dy*p.x - dx*p.y + lConstant);
				double dist      = numerator/denominator;
				if(dist>maxDist){
					index = i;
					maxDist = dist;
				}
			}
		}
		if(maxDist<=epsilon){
			return List.of(lineStart, lineEnd);
		}
		if(points.size()<=3){
			return points;
		}
		
		var left  = douglasPeucker(points.subList(0, index + 1), epsilon);
		var right = douglasPeucker(points.subList(index, points.size()), epsilon);
		
		List<Vector2f> result = new ArrayList<>(left.size() - 1 + right.size());
		result.addAll(left);
		result.removeLast();
		result.addAll(right);
		return result;
	}
	
	private static DistMax computeZeroLine(List<Vector2f> points, Vector2f lineStart){
		int    index   = -1;
		double maxDist = 0;
		for(int i = 1, s = points.size() - 1; i<s; i++){
			Vector2f p = points.get(i);
			
			double dist = Math.hypot(p.x - lineStart.x, p.y - lineStart.y);
			if(dist>maxDist){
				index = i;
				maxDist = dist;
			}
		}
		return new DistMax(index, maxDist);
	}
	
	
	public static List<Vector2f> catmullRomToInterpolated(List<Vector2f> points, int stepsPerSegment){
		return catmullRomToInterpolated(points, 1, stepsPerSegment);
	}
	public static List<Vector2f> catmullRomToInterpolated(List<Vector2f> points, double tension, int stepsPerSegment){
		switch(points.size()){
			case 0, 1 -> { return new ArrayList<>(); }
			case 2 -> { return points; }
		}
		List<Vector2f> result = new ArrayList<>(1 + stepsPerSegment*(points.size() - 1));
		
		for(int i = 0; i<points.size() - 1; i++){
			var p0 = i>0? points.get(i - 1) : points.get(i);
			var p1 = points.get(i);
			var p2 = points.get(i + 1);
			var p3 = (i + 2<points.size())? points.get(i + 2) : p2;
			
			var c1 = new Vector2f(
				(float)(p1.x + (p2.x - p0.x)*tension/6.0),
				(float)(p1.y + (p2.y - p0.y)*tension/6.0)
			);
			var c2 = new Vector2f(
				(float)(p2.x - (p3.x - p1.x)*tension/6.0),
				(float)(p2.y - (p3.y - p1.y)*tension/6.0)
			);
			
			interpolateBezier(result, p1, c1, c2, p2, stepsPerSegment, i == 0? 0 : 1);
		}
		
		return result;
	}
	
	public static List<Vector2f> interpolateBezier(Vector2f p0, Vector2f p1, Vector2f p2, Vector2f p3, int steps){
		return interpolateBezier(new ArrayList<>(steps + 1), p0, p1, p2, p3, steps, 0);
	}
	public static List<Vector2f> interpolateBezier(List<Vector2f> dest, Vector2f p0, Vector2f p1, Vector2f p2, Vector2f p3, int steps, int start){
		for(int i = start; i<=steps; i++){
			double t = i/(double)steps;
			double x = Math.pow(1 - t, 3)*p0.x +
			           3*Math.pow(1 - t, 2)*t*p1.x +
			           3*(1 - t)*Math.pow(t, 2)*p2.x +
			           Math.pow(t, 3)*p3.x;
			double y = Math.pow(1 - t, 3)*p0.y +
			           3*Math.pow(1 - t, 2)*t*p1.y +
			           3*(1 - t)*Math.pow(t, 2)*p2.y +
			           Math.pow(t, 3)*p3.y;
			dest.add(new Vector2f((float)x, (float)y));
		}
		return dest;
	}
	
}

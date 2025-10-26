package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.DrawUtils;
import com.lapissea.dfs.tools.newlogger.display.IndexBuilder;
import com.lapissea.dfs.tools.newlogger.display.VertexBuilder;
import org.joml.Intersectionf;
import org.joml.Matrix2f;
import org.joml.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Geometry{
	
	public interface Path{
		PointsLine toPoints();
		DrawUtils.Rect boundingBox();
	}
	
	public record PointsLine(List<Vector2f> points, float width, Color color, boolean miterJoints) implements Path{
		@Override
		public PointsLine toPoints(){ return this; }
		@Override
		public DrawUtils.Rect boundingBox(){
			if(points.isEmpty()){
				return DrawUtils.Rect.ofWH(0, 0, 0, 0);
			}
			var min = new Vector2f(points.getFirst());
			var max = new Vector2f(points.getFirst());
			
			for(Vector2f point : points){
				min.min(point);
				max.max(point);
			}
			var rad = width/2;
			min.sub(width, rad);
			max.add(width, rad);
			return DrawUtils.Rect.ofFromTo(min, max);
		}
	}
	
	public record BezierCurve(List<Vector2f> controlPoints, float width, Color color, int resolution, double epsilon) implements Path{
		@Override
		public PointsLine toPoints(){
			var points  = catmullRomToInterpolated(controlPoints, resolution);
			var reduced = epsilon>0? douglasPeuckerReduce(points, epsilon) : points;
			return new PointsLine(reduced, width, color, false);
		}
		@Override
		public DrawUtils.Rect boundingBox(){
			return catmullRomBounds(controlPoints, width/2);
		}
		
	}
	
	public record Vertex(Vector2f pos, Color color){ }
	
	public record IndexedMesh(VertexBuilder verts, IndexBuilder indices){
		public DrawUtils.Rect boundingBox(){
			if(verts.size() == 0){
				return DrawUtils.Rect.ofWH(0, 0, 0, 0);
			}
			var min = new Vector2f(verts.getPos(0));
			var max = new Vector2f(verts.getPos(0));
			for(int i = 0; i<verts.size(); i++){
				var point = verts.getPos(i);
				min.min(point);
				max.max(point);
			}
			return DrawUtils.Rect.ofFromTo(min, max);
		}
		
		public void add(IndexedMesh mesh){
			var offset = verts.size();
			verts.add(mesh.verts);
			indices.addOffset(mesh.indices, offset);
		}
	}
	
	public record MeshSize(int vertCount, int indexCount){ }
	static MeshSize calculateMeshSize(PointsLine line){
		int pointCount = line.points().size();
		if(pointCount<2) return new MeshSize(0, 0);
		return new MeshSize(2*pointCount, 6*(pointCount - 1));
	}
	static MeshSize calculateMeshSize(Iterable<PointsLine> lines){
		int vertCount = 0, indexCount = 0;
		for(PointsLine line : lines){
			var s = calculateMeshSize(line);
			vertCount += s.vertCount;
			indexCount += s.indexCount;
		}
		return new MeshSize(vertCount, indexCount);
	}
	static IndexedMesh generateThickLineMesh(PointsLine line){
		
		var size  = calculateMeshSize(line);
		var verts = new VertexBuilder(size.vertCount);
		
		var pts = line.points();
		if(pts.size()<2) return new IndexedMesh(verts, new IndexBuilder());
		
		float halfWidth = line.width()*0.5f;
		Color color     = line.color();
		
		{
			Vector2f p1    = pts.get(0), p2 = pts.get(1);
			double   angle = -Math.atan2(p1.y - p2.y, p1.x - p2.x);
			Vector2f mx    = new Vector2f((float)Math.sin(angle), (float)Math.cos(angle)).mul(halfWidth);
			verts.add(p1.add(mx, new Vector2f()), color);
			verts.add(p1.sub(mx, new Vector2f()), color);
		}
		
		var intersect = new Vector2f();
		for(int i = 1, s = pts.size() - 1; i<s; i++){
			var prev    = pts.get(i - 1);
			var current = pts.get(i);
			var next    = pts.get(i + 1);
			
			if(line.miterJoints && computeMitterIntersect(prev, current, next, halfWidth, intersect)){
				var intersect1 = new Vector2f(intersect);
				var intersect2 = new Vector2f(intersect).add(current.sub(intersect, new Vector2f()).mul(2));
				
				verts.add(intersect1, color);
				verts.add(intersect2, color);
			}else{
				var mx = computeAvgAngleOff(prev, current, next, halfWidth);
				verts.add(current.add(mx, new Vector2f()), color);
				verts.add(current.sub(mx, new Vector2f()), color);
			}
		}
		
		{
			int      i     = pts.size() - 1;
			Vector2f p1    = pts.get(i), p2 = pts.get(i - 1);
			double   angle = -Math.atan2(p2.y - p1.y, p2.x - p1.x);
			Vector2f mx    = new Vector2f((float)Math.sin(angle), (float)Math.cos(angle)).mul(halfWidth);
			verts.add(p1.add(mx, new Vector2f()), color);
			verts.add(p1.sub(mx, new Vector2f()), color);
		}
		
		int[] quad1 = {0, 1, 2,
		               1, 3, 2};
		int[] quad2 = {0, 1, 3,
		               0, 3, 2};
		
		var indices = new IndexBuilder(size).noResize();
		
		for(int idx = 0; idx<size.vertCount - 2; idx += 2){
			int i0 = idx, i1 = idx + 1, i2 = idx + 2, i3 = idx + 3;
			
			var len1 = verts.getPos(i0).distance(verts.getPos(i3));
			var len2 = verts.getPos(i1).distance(verts.getPos(i2));
			
			var quad = len1>len2? quad1 : quad2;
			indices.addOffset(quad, idx);
		}
		assert verts.size() == size.vertCount : verts.size() + " != " + size.vertCount;
		
		return new IndexedMesh(verts, indices);
	}
	
	private static Vector2f computeAvgAngleOff(Vector2f prev, Vector2f current, Vector2f next, float halfWidth){
		var dir = new Vector2f();
		prev.sub(current, dir);
		dir.normalize().add(current.sub(next, new Vector2f()).normalize());
		
		var angle = -Math.atan2(dir.y, dir.x);
		
		return new Vector2f((float)Math.sin(angle), (float)Math.cos(angle)).mul(halfWidth);
	}
	private static boolean computeMitterIntersect(Vector2f prev, Vector2f current, Vector2f next, float width, Vector2f intersect){
		var dir1 = prev.sub(current, new Vector2f()).normalize(width).mul(new Matrix2f().rotate((float)(Math.PI/2)));
		var dir2 = current.sub(next, new Vector2f()).normalize(width).mul(new Matrix2f().rotate((float)(Math.PI/2)));
		
		var segment1l1p1 = prev.add(dir1, new Vector2f());
		var segment1l1p2 = current.add(dir1, new Vector2f());
		
		var segment1l2p1 = current.add(dir2, new Vector2f());
		var segment1l2p2 = next.add(dir2, new Vector2f());
		
		if(!Intersectionf.intersectLineLine(segment1l1p1.x, segment1l1p1.y, segment1l1p2.x, segment1l1p2.y,
		                                    segment1l2p1.x, segment1l2p1.y, segment1l2p2.x, segment1l2p2.y, intersect)) return false;
		
		var off = intersect.sub(current, new Vector2f());
		if(off.length()>width*4){
			intersect.set(current).add(off.normalize(width*4));
		}
		return true;
	}
	
	
	private record DistMax(int index, double maxDist){ }
	
	private record Slice(Vector2f[] arr, int start, int end){
		
		Slice(List<Vector2f> points){
			this(points.toArray(Vector2f[]::new));
		}
		Slice(Vector2f[] points){
			this(points, 0, points.length);
		}
		Slice{
			assert end>=start;
			assert arr.length>=end;
			assert start>=0;
		}
		
		int size()         { return end - start; }
		Vector2f getFirst(){ return arr[start]; }
		Vector2f getLast() { return arr[end - 1]; }
		Vector2f get(int i){ return arr[start + i]; }
		
		Slice subSlice(int fromIndex, int toIndex){
			return new Slice(arr, start + fromIndex, start + toIndex);
		}
		List<Vector2f> asList(){
			return Arrays.asList(arr).subList(start, end);
		}
	}
	
	public static List<Vector2f> douglasPeuckerReduce(List<Vector2f> points, double epsilon){
		var result = douglasPeuckerReduce(new Slice(points), epsilon);
		return List.copyOf(result.asList());
	}
	private static Slice douglasPeuckerReduce(Slice points, double epsilon){
		var pSize = points.size();
		if(pSize<3){
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
			var lConstant   = lineEnd.x*lineStart.y - lineEnd.y*lineStart.x;
			var denominator = Math.hypot(dx, dy);
			for(int i = 1, s = pSize - 1; i<s; i++){
				var p = points.get(i);
				
				var numerator = Math.abs(dy*p.x - dx*p.y + lConstant);
				var dist      = numerator/denominator;
				if(dist>maxDist){
					index = i;
					maxDist = dist;
				}
			}
		}
		if(maxDist<=epsilon){
			return new Slice(new Vector2f[]{lineStart, lineEnd}, 0, 2);
		}
		if(pSize == 3){
			return points;
		}
		
		var left  = douglasPeuckerReduce(points.subSlice(0, index + 1), epsilon);
		var right = douglasPeuckerReduce(points.subSlice(index, pSize), epsilon);
		
		int leftSize = left.size() - 1, rightSize = right.size();
		
		System.arraycopy(left.arr, left.start, points.arr, points.start, leftSize);
		System.arraycopy(right.arr, right.start, points.arr, points.start + leftSize, rightSize);
		
		return new Slice(points.arr, points.start, points.start + leftSize + rightSize);
	}
	
	private static DistMax computeZeroLine(Slice points, Vector2f lineStart){
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
	
	public static void interpolateBezier(List<Vector2f> dest, Vector2f p0, Vector2f p1, Vector2f p2, Vector2f p3, int steps, int start){
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
	}
	
	public static DrawUtils.Rect catmullRomBounds(List<Vector2f> points, float rad){
		if(points.size()<2) return DrawUtils.Rect.ofWH(0, 0, 0, 0);
		
		float minX = Float.POSITIVE_INFINITY;
		float minY = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY;
		float maxY = Float.NEGATIVE_INFINITY;
		
		for(int i = 0; i<points.size() - 1; i++){
			var p0 = i>0? points.get(i - 1) : points.get(i);
			var p1 = points.get(i);
			var p2 = points.get(i + 1);
			var p3 = (i + 2<points.size())? points.get(i + 2) : p2;
			
			Vector2f c1 = new Vector2f(
				(float)(p1.x + (p2.x - p0.x)/6.0),
				(float)(p1.y + (p2.y - p0.y)/6.0)
			);
			Vector2f c2 = new Vector2f(
				(float)(p2.x - (p3.x - p1.x)/6.0),
				(float)(p2.y - (p3.y - p1.y)/6.0)
			);
			
			// check extrema for this cubic
			float[] xs = bezierExtrema(p1.x, c1.x, c2.x, p2.x);
			float[] ys = bezierExtrema(p1.y, c1.y, c2.y, p2.y);
			
			for(float t : xs){
				Vector2f pt = cubicPoint(p1, c1, c2, p2, t);
				minX = Math.min(minX, pt.x);
				maxX = Math.max(maxX, pt.x);
				minY = Math.min(minY, pt.y);
				maxY = Math.max(maxY, pt.y);
			}
			for(float t : ys){
				Vector2f pt = cubicPoint(p1, c1, c2, p2, t);
				minX = Math.min(minX, pt.x);
				maxX = Math.max(maxX, pt.x);
				minY = Math.min(minY, pt.y);
				maxY = Math.max(maxY, pt.y);
			}
		}
		
		minX -= rad;
		maxX += rad;
		minY -= rad;
		maxY += rad;
		
		return DrawUtils.Rect.ofFromTo(minX, minY, maxX, maxY);
	}
	
	private static float[] bezierExtrema(float p0, float p1, float p2, float p3){
		float a = -p0 + 3*p1 - 3*p2 + p3;
		float b = 2*(p0 - 2*p1 + p2);
		float c = -p0 + p1;
		
		float[] result = new float[4];
		int     count  = 0;
		
		result[count++] = 0f;
		result[count++] = 1f;
		
		float discriminant = b*b - 4*a*c;
		if(Math.abs(a)<1e-8){
			if(Math.abs(b)>1e-8){
				float t = -c/b;
				if(t>0 && t<1) result[count++] = t;
			}
		}else if(discriminant>=0){
			float sqrtD = (float)Math.sqrt(discriminant);
			float t1    = (-b + sqrtD)/(2*a);
			float t2    = (-b - sqrtD)/(2*a);
			if(t1>0 && t1<1) result[count++] = t1;
			if(t2>0 && t2<1) result[count++] = t2;
		}
		
		return Arrays.copyOf(result, count);
	}
	
	private static Vector2f cubicPoint(Vector2f p0, Vector2f p1, Vector2f p2, Vector2f p3, float t){
		float u   = 1 - t;
		float tt  = t*t;
		float uu  = u*u;
		float uuu = uu*u;
		float ttt = tt*t;
		
		float x = uuu*p0.x + 3*uu*t*p1.x + 3*u*tt*p2.x + ttt*p3.x;
		float y = uuu*p0.y + 3*uu*t*p1.y + 3*u*tt*p2.y + ttt*p3.y;
		return new Vector2f(x, y);
	}
	
	
}

package com.lapissea.dfs.inspect.display.primitives;

import com.lapissea.dfs.inspect.display.renderers.BezierCurveRes;
import com.lapissea.dfs.tools.DrawUtils;
import org.joml.Vector2f;

import java.awt.Color;
import java.util.List;

public interface Path{
	PointsLine toPoints();
	DrawUtils.Rect boundingBox();
	
	record PointsLine(List<Vector2f> points, float width, Color color, boolean miterJoints) implements Path{
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
		
		public Geometry.MeshSize calculateMeshSize(){
			int pointCount = points.size();
			if(pointCount<2) return new Geometry.MeshSize(0, 0);
			return new Geometry.MeshSize(2*pointCount, 6*(pointCount - 1));
		}
	}
	
	record BezierCurve(List<Vector2f> controlPoints, float width, Color color, int resolution, float epsilon) implements Path{
		public BezierCurve(List<Vector2f> controlPoints, float width, Color color, int resolution){
			this(controlPoints, width, color, resolution, 0.3F);
		}
		@Override
		public PointsLine toPoints(){
			var points  = new BezierCurveRes(controlPoints).sample(resolution);
			var reduced = epsilon>0? Geometry.douglasPeuckerReduce(points, epsilon) : points;
			return new PointsLine(reduced, width, color, false);
//			return new PointsLine(controlPoints, width, color, false);
		}
		@Override
		public DrawUtils.Rect boundingBox(){
			if(controlPoints.isEmpty()) return new DrawUtils.Rect(0, 0, 0, 0);
			Vector2f min = new Vector2f(controlPoints.getFirst());
			Vector2f max = new Vector2f(controlPoints.getFirst());
			for(Vector2f controlPoint : controlPoints){
				min.min(controlPoint);
				max.max(controlPoint);
			}
			return DrawUtils.Rect.ofFromTo(min, max);
		}
		
	}
	
	record CatmullRomCurve(List<Vector2f> controlPoints, float width, Color color, int resolution, float epsilon) implements Path{
		public CatmullRomCurve(List<Vector2f> controlPoints, float width, Color color, int resolution){
			this(controlPoints, width, color, resolution, 0.3F);
		}
		@Override
		public PointsLine toPoints(){
			var points  = Geometry.catmullRomToInterpolated(controlPoints, resolution);
			var reduced = epsilon>0? Geometry.douglasPeuckerReduce(points, epsilon) : points;
			return new PointsLine(reduced, width, color, false);
//			return new PointsLine(controlPoints, width, color, false);
		}
		@Override
		public DrawUtils.Rect boundingBox(){
			return Geometry.catmullRomBounds(controlPoints, width/2);
		}
		
	}
}

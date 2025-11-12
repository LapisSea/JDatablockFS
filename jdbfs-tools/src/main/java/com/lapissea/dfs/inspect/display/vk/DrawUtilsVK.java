package com.lapissea.dfs.inspect.display.vk;

import com.lapissea.dfs.inspect.display.grid.GridUtils;
import com.lapissea.dfs.inspect.display.primitives.Geometry;
import com.lapissea.dfs.tools.DrawUtils;

import java.awt.Color;

public final class DrawUtilsVK{
	
	public static void fillByteRange(GridUtils.ByteGridSize gridSize, Geometry.IndexedMesh dest, Color color, DrawUtils.Range range){
		long from = range.from();
		long to   = range.to();
		
		var viewSize = gridSize.bytesPerRow();
		
		//tail
		long fromX      = from%viewSize;
		long rightSpace = Math.min(viewSize - fromX, to - from);
		if(rightSpace>0){
			fillByteRect(gridSize, dest, color, from, rightSpace, 1);
			from += rightSpace;
		}
		
		//bulk
		long bulkColumns = (to - from)/viewSize;
		if(bulkColumns>0){
			fillByteRect(gridSize, dest, color, from, viewSize, bulkColumns);
			from += bulkColumns*viewSize;
		}
		
		//head
		if(to>from){
			fillByteRect(gridSize, dest, color, from, to - from, 1);
		}
	}
	
	public static void fillByteRect(GridUtils.ByteGridSize gridSize, Geometry.IndexedMesh dest, Color color, long start, long width, long columnCount){
		var bytesPerRow = gridSize.bytesPerRow();
		var xi          = (int)(start%bytesPerRow);
		var yStart      = (int)(start/bytesPerRow);
		
		fillQuad(
			dest, color,
			gridSize.byteSize()*xi,
			gridSize.byteSize()*yStart,
			gridSize.byteSize()*width,
			gridSize.byteSize()*columnCount
		);
	}
	
	private static final int[] quad = {0, 2, 1, 0, 3, 2};
	public static void fillQuad(Geometry.IndexedMesh dest, Color color, float x, float y, float width, float height){
		var vts = dest.verts();
		var off = vts.size();
		vts.add(x, y, color);
		vts.add(x + width, y, color);
		vts.add(x + width, y + height, color);
		vts.add(x, y + height, color);
		dest.indices().addOffset(quad, off);
	}
}

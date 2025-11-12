package com.lapissea.dfs.inspect.display.renderers;

import com.lapissea.dfs.inspect.display.DeviceGC;
import com.lapissea.dfs.inspect.display.VulkanCodeException;
import com.lapissea.dfs.inspect.display.primitives.Geometry;
import com.lapissea.dfs.inspect.display.primitives.IndexBuilder;
import com.lapissea.dfs.inspect.display.primitives.Path;
import com.lapissea.dfs.inspect.display.primitives.VertexBuilder;
import com.lapissea.dfs.inspect.display.vk.CommandBuffer;
import com.lapissea.dfs.inspect.display.vk.VulkanResource;
import com.lapissea.dfs.inspect.display.vk.wrap.Extent2D;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.joml.Matrix3x2f;

public class LineRenderer implements VulkanResource{
	
	private final IndexedMeshRenderer meshRenderer;
	private final boolean             owningRenderer;
	
	public LineRenderer(IndexedMeshRenderer meshRenderer, boolean owningRenderer){
		this.meshRenderer = meshRenderer;
		this.owningRenderer = owningRenderer;
	}
	
	public IndexedMeshRenderer.RToken record(DeviceGC deviceGC, Renderer.IndexedMeshBuffer resource, Iterable<? extends Path> paths) throws VulkanCodeException{
		
		var lines = Iters.from(paths).map(Path::toPoints).toList();
		
		var size = Geometry.MeshSize.calculate(lines);
		if(size.vertCount() == 0) return null;
		
		var vertices    = new VertexBuilder(size.vertCount());
		var verticesPos = 0;
		
		var indices = new IndexBuilder(size).noResize();
		
		for(var mesh : Iters.from(lines).map(Geometry::generateThickLineMesh)){
			var off = verticesPos;
			
			var siz   = mesh.verts().size();
			var xy    = mesh.verts().getXy();
			var color = mesh.verts().getColor();
			for(int i = 0; i<siz; i++){
				vertices.add(xy[i*2], xy[i*2 + 1], color[i]);
			}
			verticesPos += siz;
			indices.addOffset(mesh.indices(), off);
		}
		
		return meshRenderer.record(deviceGC, resource, new Geometry.IndexedMesh(vertices, indices));
	}
	
	public void submit(Extent2D viewSize, CommandBuffer buf, Matrix3x2f pvm, Iterable<IndexedMeshRenderer.RToken> tokens) throws VulkanCodeException{
		meshRenderer.submit(viewSize, buf, pvm, tokens);
	}
	
	@Override
	public void destroy() throws VulkanCodeException{
		if(owningRenderer){
			meshRenderer.destroy();
		}
	}
}

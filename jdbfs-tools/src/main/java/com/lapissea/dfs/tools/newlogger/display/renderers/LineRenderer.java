package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.newlogger.display.DeviceGC;
import com.lapissea.dfs.tools.newlogger.display.IndexBuilder;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.joml.Matrix3x2f;

public class LineRenderer implements VulkanResource{
	
	private final IndexedMeshRenderer meshRenderer;
	private final boolean             owningRenderer;
	
	public LineRenderer(IndexedMeshRenderer meshRenderer, boolean owningRenderer){
		this.meshRenderer = meshRenderer;
		this.owningRenderer = owningRenderer;
	}
	
	public IndexedMeshRenderer.RToken record(DeviceGC deviceGC, Renderer.IndexedMeshBuffer resource, Iterable<? extends Geometry.Path> paths) throws VulkanCodeException{
		
		var lines = Iters.from(paths).map(Geometry.Path::toPoints).toList();
		
		var size = Geometry.calculateMeshSize(lines);
		if(size.vertCount() == 0) return null;
		
		var vertices    = new Geometry.Vertex[size.vertCount()];
		var verticesPos = 0;
		
		var indices = new IndexBuilder(size).noResize();
		
		for(var mesh : Iters.from(lines).map(Geometry::generateThickLineMesh)){
			var off = verticesPos;
			for(Geometry.Vertex vert : mesh.verts()){
				vertices[verticesPos++] = new Geometry.Vertex(vert.pos(), vert.color());
			}
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

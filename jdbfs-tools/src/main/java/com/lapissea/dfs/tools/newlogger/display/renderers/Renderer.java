package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.newlogger.display.DeviceGC;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.BackedVkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.MappedVkMemory;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;

public interface Renderer<RB extends Renderer.ResourceBuffer, RT extends Renderer.RenderToken> extends VulkanResource{
	
	
	final class IndexedMeshBuffer implements VulkanResource{
		
		private BackedVkBuffer vbos;
		private BackedVkBuffer ibos;
		private long           vboPos;
		private long           iboPos;
		
		@Override
		public void destroy() throws VulkanCodeException{
			if(vbos == null) return;
			vbos.destroy();
			vbos = null;
			ibos.destroy();
			ibos = null;
		}
		
		public BackedVkBuffer vbos(){ return vbos; }
		public BackedVkBuffer ibos(){ return ibos; }
		
		public void reset(){
			vboPos = iboPos = 0;
		}
		
		public record VBIBMem(MappedVkMemory vboMem, MappedVkMemory iboMem) implements AutoCloseable{
			@Override
			public void close() throws VulkanCodeException{
				vboMem.close();
				iboMem.close();
			}
		}
		
		public VBIBMem requestMemory(DeviceGC deviceGC, Device device, long vboMemory, long iboMemory) throws VulkanCodeException{
			var oldVboSize = vbos == null? 0 : vbos.size();
			var oldIboSize = ibos == null? 0 : ibos.size();
			
			var newVboSize = vboPos + vboMemory;
			var newIboSize = iboPos + iboMemory;
			
			if(vbos == null || newVboSize>oldVboSize){
				var newBuff = device.allocateHostBuffer(newVboSize, VkBufferUsageFlag.VERTEX_BUFFER);
				if(vbos != null){
					vbos.transferTo(newBuff, vboPos);
					deviceGC.destroyLater(vbos);
				}
				vbos = newBuff;
			}
			if(ibos == null || newIboSize>oldIboSize){
				var newBuff = device.allocateHostBuffer(newIboSize, VkBufferUsageFlag.INDEX_BUFFER);
				if(ibos != null){
					ibos.transferTo(newBuff, iboPos);
					deviceGC.destroyLater(ibos);
				}
				ibos = newBuff;
			}
			
			var vboMem = vbos.update(vboPos, vboMemory);
			var iboMem = ibos.update(iboPos, iboMemory);
			vboPos += vboMemory;
			iboPos += iboMemory;
			return new VBIBMem(vboMem, iboMem);
		}
		
	}
	
	interface ResourceBuffer extends VulkanResource{ }
	
	interface RenderToken{ }
	
}

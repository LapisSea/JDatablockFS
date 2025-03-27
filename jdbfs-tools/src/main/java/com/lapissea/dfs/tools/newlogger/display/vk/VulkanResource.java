package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;

public interface VulkanResource extends AutoCloseable{
	
	abstract class DeviceHandleObj implements VulkanResource{
		public final Device device;
		public final long   handle;
		
		
		protected DeviceHandleObj(Device device, long handle){
			this.device = device;
			this.handle = handle;
			
			if(VulkanCore.VK_DEBUG){
				device.debugVkObjects.put(handle, getTrace());
			}
		}
		
		protected void logCreationDebug(){
			getTrace().printStackTrace();
		}
		private Throwable getTrace(){
			return new Throwable(this.getClass().getSimpleName() + " INIT: 0x" + Long.toUnsignedString(handle, 16));
		}
	}
	
	default void close(){ destroy(); }
	void destroy();
}

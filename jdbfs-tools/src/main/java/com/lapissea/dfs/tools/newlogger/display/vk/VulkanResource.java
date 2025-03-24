package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;

public interface VulkanResource extends AutoCloseable{
	
	abstract class DeviceHandleObj implements VulkanResource{
		public final Device device;
		public final long   handle;
		
		protected DeviceHandleObj(Device device, long handle){
			this.device = device;
			this.handle = handle;
		}
		
		protected void logCreationDebug(){
			new Throwable(this.getClass().getSimpleName() + " INIT: 0x" + Long.toUnsignedString(handle, 16)).printStackTrace();
		}
	}
	
	default void close(){ destroy(); }
	void destroy();
}

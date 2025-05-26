package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;
import com.lapissea.dfs.utils.iterableplus.Iters;

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
			var t = new Throwable(this.getClass().getSimpleName() + " INIT: 0x" + Long.toUnsignedString(handle, 16));
			t.setStackTrace(Iters.from(t.getStackTrace())
			                     .dropWhile(e -> e.getClassName().equals(DeviceHandleObj.class.getName()))
			                     .toArray(StackTraceElement[]::new));
			return t;
		}
		
		@Override
		public int hashCode(){
			return Long.hashCode(handle);
		}
	}
	
	default void close() throws VulkanCodeException{ destroy(); }
	void destroy() throws VulkanCodeException;
}

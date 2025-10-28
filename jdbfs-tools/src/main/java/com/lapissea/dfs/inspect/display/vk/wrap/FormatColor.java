package com.lapissea.dfs.inspect.display.vk.wrap;

import com.lapissea.dfs.inspect.display.vk.enums.VkColorSpaceKHR;
import com.lapissea.dfs.inspect.display.vk.enums.VkFormat;

import java.util.Objects;

public class FormatColor{
	
	public final VkFormat        format;
	public final VkColorSpaceKHR colorSpace;
	
	public FormatColor(int format, int colorSpace){
		this(VkFormat.from(format), VkColorSpaceKHR.from(colorSpace));
	}
	public FormatColor(VkFormat format, VkColorSpaceKHR colorSpace){
		this.format = Objects.requireNonNull(format);
		this.colorSpace = Objects.requireNonNull(colorSpace);
	}
	
	@Override
	public final boolean equals(Object o){
		if(!(o instanceof FormatColor that)) return false;
		return format == that.format && colorSpace == that.colorSpace;
	}
	@Override
	public int hashCode(){
		int result = format.hashCode();
		result = 31*result + colorSpace.hashCode();
		return result;
	}
	@Override
	public String toString(){
		return format + "/" + colorSpace;
	}
}

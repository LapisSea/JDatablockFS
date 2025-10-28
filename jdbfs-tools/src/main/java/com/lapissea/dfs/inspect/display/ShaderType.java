package com.lapissea.dfs.inspect.display;

import com.lapissea.dfs.inspect.display.vk.enums.VkShaderStageFlag;

public enum ShaderType{
	VERTEX("vert", VkShaderStageFlag.VERTEX),
	GEOMETRY("geom", VkShaderStageFlag.GEOMETRY),
	FRAGMENT("frag", VkShaderStageFlag.FRAGMENT),
	COMPUTE("comp", VkShaderStageFlag.COMPUTE),
	;
	
	public final String            extension;
	public final VkShaderStageFlag vkFlag;
	ShaderType(String extension, VkShaderStageFlag vkFlag){
		this.extension = extension;
		this.vkFlag = vkFlag;
	}
}

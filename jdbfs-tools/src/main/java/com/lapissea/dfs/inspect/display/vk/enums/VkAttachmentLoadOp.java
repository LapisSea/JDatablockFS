package com.lapissea.dfs.inspect.display.vk.enums;

import com.lapissea.dfs.inspect.display.VUtils;

import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK14.VK_ATTACHMENT_LOAD_OP_NONE;

public enum VkAttachmentLoadOp implements VUtils.IDValue{
	
	LOAD(VK_ATTACHMENT_LOAD_OP_LOAD),
	CLEAR(VK_ATTACHMENT_LOAD_OP_CLEAR),
	DONT_CARE(VK_ATTACHMENT_LOAD_OP_DONT_CARE),
	NONE(VK_ATTACHMENT_LOAD_OP_NONE),
	;
	
	public final int id;
	VkAttachmentLoadOp(int id){ this.id = id; }
	@Override
	public int id(){ return id; }
	
	public static VkAttachmentLoadOp from(int id){ return VUtils.fromID(VkAttachmentLoadOp.class, id); }
}

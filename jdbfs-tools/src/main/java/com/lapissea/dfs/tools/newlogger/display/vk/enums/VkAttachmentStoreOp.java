package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;

import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK13.VK_ATTACHMENT_STORE_OP_NONE;

public enum VkAttachmentStoreOp implements VUtils.IDValue{
	
	STORE(VK_ATTACHMENT_STORE_OP_STORE),
	DONT_CARE(VK_ATTACHMENT_STORE_OP_DONT_CARE),
	NONE(VK_ATTACHMENT_STORE_OP_NONE),
	;
	
	public final int id;
	VkAttachmentStoreOp(int id){ this.id = id; }
	@Override
	public int id(){ return id; }
	
	public static VkAttachmentStoreOp from(int id){ return VUtils.fromID(VkAttachmentStoreOp.class, id); }
	
}

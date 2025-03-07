package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK13.VK_ATTACHMENT_STORE_OP_NONE;

public enum VkAttachmentStoreOp{
	
	STORE(VK_ATTACHMENT_STORE_OP_STORE),
	DONT_CARE(VK_ATTACHMENT_STORE_OP_DONT_CARE),
	NONE(VK_ATTACHMENT_STORE_OP_NONE),
	;
	
	public final int id;
	VkAttachmentStoreOp(int id){ this.id = id; }
	
	private static final Map<Integer, VkAttachmentStoreOp> BY_ID = Iters.from(VkAttachmentStoreOp.class).toMap(e -> e.id, e -> e);
	
	public static VkAttachmentStoreOp from(int id){
		var value = BY_ID.get(id);
		if(value == null){
			throw new IllegalArgumentException("Unknown id: " + id);
		}
		return value;
	}
	
}

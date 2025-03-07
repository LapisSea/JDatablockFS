package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK14.VK_ATTACHMENT_LOAD_OP_NONE;

public enum VkAttachmentLoadOp{
	
	LOAD(VK_ATTACHMENT_LOAD_OP_LOAD),
	CLEAR(VK_ATTACHMENT_LOAD_OP_CLEAR),
	DONT_CARE(VK_ATTACHMENT_LOAD_OP_DONT_CARE),
	NONE(VK_ATTACHMENT_LOAD_OP_NONE),
	;
	
	public final int id;
	VkAttachmentLoadOp(int id){ this.id = id; }
	
	private static final Map<Integer, VkAttachmentLoadOp> BY_ID = Iters.from(VkAttachmentLoadOp.class).toMap(e -> e.id, e -> e);
	
	public static VkAttachmentLoadOp from(int id){
		var value = BY_ID.get(id);
		if(value == null){
			throw new IllegalArgumentException("Unknown id: " + id);
		}
		return value;
	}
	
}

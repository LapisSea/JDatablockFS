package com.lapissea.dfs.tools.newlogger.display.vk;

public abstract class PipelineBinding{
	
	final class StorageBuffer extends PipelineBinding{
		
		StorageBuffer(int binding){ super(binding); }
		
	}
	
	final class Uniform extends PipelineBinding{
		
		Uniform(int binding){ super(binding); }
		
	}
	
	final class TextureSampler extends PipelineBinding{
		
		TextureSampler(int binding){
			super(binding);
		}
	}
	
	public final int binding;
	
	protected PipelineBinding(int binding){ this.binding = binding; }
}

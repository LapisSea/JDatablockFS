package com.lapissea.dfs.inspect.display.vk;

import com.lapissea.dfs.inspect.display.ShaderType;
import com.lapissea.dfs.inspect.display.VulkanCodeException;
import com.lapissea.dfs.inspect.display.vk.wrap.ShaderModule;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class ShaderModuleSet extends AbstractList<ShaderModule> implements VulkanResource{
	
	private final CompletableFuture<ShaderModule[]> task;
	private       ShaderModule[]                    modules;
	
	public ShaderModuleSet(VulkanCore core, String name, ShaderType... types){
		task = CompletableFuture.supplyAsync(() -> {
			var tasks = new ArrayList<CompletableFuture<ShaderModule>>(types.length);
			for(ShaderType type : types){
				tasks.add(CompletableFuture.supplyAsync(() -> {
					try{
						var spirv  = core.sourceToSpirv(name, type);
						var module = core.createShaderModule(spirv, type);
						module.name = name;
						return module;
					}catch(VulkanCodeException e){
						throw UtilL.uncheckedThrow(e);
					}
				}));
			}
			ShaderModule[] modules = new ShaderModule[tasks.size()];
			for(int i = 0; i<tasks.size(); i++){
				var task = tasks.get(i);
				try{
					modules[i] = task.join();
				}catch(CompletionException e){
					throw UtilL.uncheckedThrow(e.getCause());
				}
			}
			LogUtil.println("Compiled shader modules:", name);
			return modules;
		});
	}
	
	private ShaderModule[] getModules(){
		if(modules == null){
			try{
				modules = task.join();
			}catch(CompletionException e){
				throw new RuntimeException("Failed to compile shader set", e.getCause());
			}
		}
		return modules;
	}
	
	@Override
	public ShaderModule get(int index){
		return getModules()[index];
	}
	
	@Override
	public int size(){
		return getModules().length;
	}
	
	@Override
	public void destroy(){
		for(ShaderModule module : getModules()){
			module.destroy();
		}
	}
}

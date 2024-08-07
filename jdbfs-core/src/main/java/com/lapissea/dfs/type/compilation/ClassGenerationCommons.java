package com.lapissea.dfs.type.compilation;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.logging.Log;
import com.lapissea.util.UtilL;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Optional;

public final class ClassGenerationCommons{
	
	private static final Optional<File> DUMP_LOCATION = ConfigDefs.CLASSGEN_DUMP_LOCATION.resolveLocking().map(File::new);
	private static final int            CHUNK_SIZE    = DUMP_LOCATION.map(File::toPath).flatMap(Utils::findPathBlockSize).orElse(1024);
	
	public static void dumpClassName(String className, byte[] data){
		DUMP_LOCATION.ifPresent(location -> Thread.startVirtualThread(() -> {
			var classPath = new File(location, className.replace('.', '/') + ".class");
			
			var classFolder = classPath.getParentFile();
			for(int i = 0, attempts = 5; i<attempts; i++){
				if(!classFolder.exists() && classFolder.mkdirs()){
					if(i != attempts - 1){
						UtilL.sleep(5);
						continue;
					}
					Log.warn("Failed to create folder(s) to {}", classFolder);
					return;
				}
			}
			
			if(classPath.exists() && classPath.length() == data.length){
				boolean equal = true;
				try(var file = new FileInputStream(classPath)){
					var buff         = new byte[Math.min(data.length, CHUNK_SIZE)];
					int read, offset = 0;
					while((read = file.read(buff)) != -1){
						if(!Arrays.equals(buff, 0, read, data, offset, offset + read)){
							equal = false;
							break;
						}
						offset += read;
					}
				}catch(IOException ignored){ }
				if(equal) return;
			}
			
			try{
				Files.write(classPath.toPath(), data);
			}catch(IOException e){
				Log.warn("Failed to dump class {} because {}", location, e);
			}
		}));
	}
	
}

package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.config.GlobalConfig;
import com.lapissea.cfs.logging.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Optional;

public class ClassGenerationCommons{
	
	private static final Optional<File> DUMP_LOCATION = GlobalConfig.configProp("classGen.dumpLocation").map(File::new);
	private static final int            CHUNK_SIZE    = DUMP_LOCATION.map(File::toPath).flatMap(Utils::findPathBlockSize).orElse(1024);
	
	public static void dumpClassName(String className, byte[] data){
		DUMP_LOCATION.ifPresent(location -> Thread.startVirtualThread(() -> {
			var classPath = new File(location, className.replace('.', '/') + ".class");
			
			var classFolder = classPath.getParentFile();
			if(!classFolder.exists() && classFolder.mkdirs()){
				Log.warn("Failed to create folder(s) to {}", classFolder);
				return;
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

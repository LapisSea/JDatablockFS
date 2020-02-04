package test;

import com.lapissea.fsf.FileSystemInFile;
import com.lapissea.fsf.Renderer;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Scanner;
import java.util.stream.Collectors;

class FSFTest_console{
	
	public static final long[] NO_IDS=new long[0];
	
	static{
//		LogUtil.Init.attach(LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER);
	}
	
	interface CmdAction{
		void run(FileSystemInFile file, String data) throws IOException;
	}
	
	enum Command{
		WRITE((file, data)->{
			StringBuilder fileName=new StringBuilder();
			for(int i=0;i<data.length();i++){
				char c=data.charAt(i);
				if(c==' ') break;
				fileName.append(c);
			}
			
			file.getFile(fileName.toString()).writeAll(data.substring(fileName.length()+1).getBytes());
		}),
		READ((file, data)->{
			LogUtil.println(file.getFile(data).readAllString());
		}),
		RENAME((file, data)->{
			throw new NotImplementedException();//TODO
		}),
		LIST((file, data)->{
			throw new NotImplementedException();//TODO
		}),
		DELETE(FileSystemInFile::delete),
		DEFRAG((file, data)->{
			file.defragment();
		}),
		;
		
		final CmdAction executor;
		
		Command(CmdAction executor){
			this.executor=executor;
		}
	}
	
	public static void main(String[] args) throws Exception{
		
		TestTemplate.run(Renderer.GUI::new, (fil, snapshot)->{
			Scanner scanner=new Scanner(System.in);
			while(true){
				var line=scanner.nextLine();
				if(line.trim().equals("EXIT")) return;
				
				StringBuilder commandStr=new StringBuilder();
				for(int i=0;i<line.length();i++){
					char c=line.charAt(i);
					if(c==' ') break;
					commandStr.append(Character.toUpperCase(c));
				}
				
				Command command;
				try{
					command=Command.valueOf(commandStr.toString());
				}catch(IllegalArgumentException e){
					LogUtil.printlnEr("Command", commandStr.toString(), "does not exist ("+Arrays.stream(Command.values()).map(Objects::toString).map(String::toLowerCase).collect(Collectors.joining(", "))+")");
					continue;
				}
				var ls=commandStr.length()+1;
				try{
					command.executor.run(fil, ls >= line.length()?"":line.substring(ls));
				}catch(IOException e){
					e.printStackTrace();
				}
			}
		});
		
	}
	
}

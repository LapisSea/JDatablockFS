package test;

import com.lapissea.fsf.FileSystemInFile;
import com.lapissea.fsf.Renderer;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.UnsafeRunnable;

import java.io.ByteArrayInputStream;
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
		WRITE_STREAM((file, data)->{
			try(var stream=file.createFile(data).write()){
				Scanner scanner=new Scanner(System.in);
				while(true){
					String ch=scanner.nextLine();
					if(ch.equals("stop")) return;
					stream.write(ch.getBytes());
				}
			}catch(IOException e){
				e.printStackTrace();
			}
		}),
		READ((file, data)->{
			LogUtil.println(file.getFile(data).readAllString());
		}),
		RENAME((file, data)->{
			throw new NotImplementedException();//TODO
		}),
		LIST((file, data)->{
			LogUtil.println(TextUtil.toTable(Arrays.asList(file.listFiles())));
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
	
	static class Foo{
		public final Object lol;
		public final int    ay;
		public final String ass;
		
		Foo(Object lol, int ay, String ass){
			this.lol=lol;
			this.ay=ay;
			this.ass=ass;
		}
	}
	
	public static void main(String[] args){
		
		TestTemplate.run(Renderer.None::new, (fil, snapshot)->{
			Scanner scanner=new Scanner(System.in);
			while(true){
				var line=scanner.nextLine();
				if(line.trim().equalsIgnoreCase("EXIT")) System.exit(0);
				
				try{
					doCommand(fil, snapshot, line);
				}catch(Throwable e){
					e.printStackTrace();
				}
			}
		});
	}
	
	public static void doCommand(FileSystemInFile fil, UnsafeRunnable<IOException> snapshot, String line) throws IOException{
		if(line.startsWith("for ")){
			var reader=new Scanner(new ByteArrayInputStream((line+"\n").getBytes()));
			reader.next("\\w*");
			var value="%"+reader.next("\\w*");
			int from =reader.nextInt();
			int to   =reader.nextInt();
			
			line=reader.nextLine().trim();
			
			while(from!=to){
				doCommand(fil, snapshot, line.replace(value, from+""));
				if(from<to) from++;
				else from--;
			}
			return;
		}
		
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
			return;
		}
		var ls=commandStr.length()+1;
		command.executor.run(fil, ls >= line.length()?"":line.substring(ls));
		snapshot.run();
	}
	
}

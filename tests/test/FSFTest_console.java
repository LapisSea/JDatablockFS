package test;

import com.lapissea.fsf.Renderer;
import com.lapissea.fsf.endpoint.IFileSystem;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.UnsafeRunnable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.function.Function;
import java.util.stream.Collectors;

class FSFTest_console{
	
	public static final long[] NO_IDS=new long[0];
	
	static{
		LogUtil.Init.attach(LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER);
	}
	
	interface CmdAction{
		void run(IFileSystem<String> file, String data) throws IOException;
	}
	
	enum Command{
		WRITE((file, data)->{
			var fileName=new StringBuilder();
			for(int i=0;i<data.length();i++){
				char c=data.charAt(i);
				if(c==' ') break;
				fileName.append(c);
			}
			
			file.createFile(fileName.toString()).writeAll(data.substring(fileName.length()+1).getBytes());
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
			var fil=file.getFile(data);
			if(fil.exists()) LogUtil.println(fil.readAllString());
			else{
				LogUtil.println("\""+data+"\" does not exist");
			}
		}),
		RENAME((file, data)->{
			int    pos =data.indexOf(" ");
			String from=data.substring(0, pos);
			var    to  =data.substring(pos+1);
			if(!file.getFile(from).rename(to)){
				LogUtil.println("Failed to rename \""+from+"\" to \""+to+'"');
			}
		}),
		LIST((file, data)->{
			LogUtil.println(TextUtil.toTable(file.listFiles()));
		}),
		DELETE((file, data)->{
			if(!file.getFile(data).delete()){
				LogUtil.println("Failed to delete: \""+data+'"');
			}
		}),
		DEFRAG((file, data)->{
			file.defragment();
		}),
		;
		
		final CmdAction executor;
		
		Command(CmdAction executor){
			this.executor=executor;
		}
	}
	
	public static void main(String[] args){
		LogUtil.Init.attach(0);
		
		TestTemplate.run(Renderer.Client::make, (fil, snapshot)->{
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
	
	//for i 0 10 write lol%i ay{1234}*%i
	public static void doCommand(IFileSystem<String> fil, UnsafeRunnable<IOException> snapshot, String line) throws IOException{
		if(line.startsWith("for ")){
			var reader=new Scanner(new ByteArrayInputStream((line+"\n").getBytes()));
			reader.next("\\w*");
			var value="%"+reader.next("\\w*");
			int from =reader.nextInt();
			int to   =reader.nextInt();
			
			var rawCmd=reader.nextLine();
			
			while(from!=to){
				int forVal=from;
				
				var command=new StringBuilder(rawCmd);
				while(Character.isWhitespace(command.charAt(0))) command.deleteCharAt(0);
				
				for(int i=0;i<command.length();i++){
					char c=command.charAt(i);
					if(c=='{'){
						var start=i+1;
						int end  =start+2;
						for(;end<command.length();end++){
							c=command.charAt(end);
							if(c=='}'){
								if(end+2+value.length()>command.length()) break;
								
								var opC=command.charAt(end+1)+"";
								var op=Map.<String, Function<String, String>>of(
									"*", s->s.repeat(forVal)
								                                               ).get(opC);
								if(op==null) break;
								
								
								var vTag=command.substring(end+2, end+2+value.length());
								if(!vTag.equals(value)) break;
								
								var valMod=op.apply(command.substring(start, end));
								command.replace(start-1, end+2+value.length(), valMod);
							}
						}
					}
				}
				int ind;
				while((ind=command.indexOf(value))!=-1){
					command.replace(ind, ind+value.length(), forVal+"");
				}
				
				doCommand(fil, snapshot, command.toString());
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
		System.gc();
		command.executor.run(fil, ls >= line.length()?"":line.substring(ls));
		snapshot.run();
	}
	
}

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
	
	interface CmdAction{
		void run(IFileSystem<String> file, StringBuilder data) throws IOException;
	}
	
	static String readWord(StringBuilder sb, String message){
		if(sb.length()==0) return "";
		if(sb.charAt(0)=='"'){
			return readUntilQuote(sb, message);
		}
		return readUntilWhitespace(sb, message);
	}
	
	static String readUntilQuote(StringBuilder sb, String message){
		var word=new StringBuilder();
		int i   =1;
		for(;i<sb.length();i++){
			char c=sb.charAt(i);
			if(c=='"'){
				break;
			}
			
			word.append(c);
		}
		
		if(word.length()==0) throw new IllegalArgumentException(message);
		
		
		sb.delete(0, i);
		
		return word.toString();
	}
	
	static String readUntilWhitespace(StringBuilder sb, String message){
		var word=new StringBuilder();
		int i   =0;
		for(;i<sb.length();i++){
			char c=sb.charAt(i);
			if(Character.isWhitespace(c)){
				for(;i<sb.length();i++){
					if(!Character.isWhitespace(sb.charAt(i))) break;
				}
				break;
			}
			
			word.append(c);
		}
		
		if(word.length()==0) throw new IllegalArgumentException(message);
		
		sb.replace(0, i, "");
		
		return word.toString();
	}
	
	enum Command{
		WRITE((file, command)->{
			
			var fileName=readWord(command, "write <file name> <data>");
			var data    =command.toString().getBytes();
			
			file.createFile(fileName).writeAll(data);
		}),
		WRITE_STREAM((file, command)->{
			try(var stream=file.createFile(readWord(command, "write_stream <file name> //write stop to end input")).write()){
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
		READ((file, command)->{
			var fil=file.getFile(readWord(command, "read <file name>"));
			if(fil.exists()) LogUtil.println(fil.readAllString());
			else{
				LogUtil.println("\""+fil.getPath()+"\" does not exist");
			}
		}),
		RENAME((file, command)->{
			String from=readWord(command, "rename <old file name> <new file name>");
			String to  =readWord(command, "rename <old file name> <new file name>");
			
			if(!file.getFile(from).rename(to)){
				LogUtil.println("Failed to rename \""+from+"\" to \""+to+'"');
			}
		}),
		LIST((file, command)->{
			LogUtil.println(TextUtil.toTable(file.listFiles()));
		}),
		DELETE((file, command)->{
			var f=file.getFile(readWord(command, "delete <file name>"));
			if(!f.delete()){
				LogUtil.println("Failed to delete: \""+f.getPath()+'"');
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
				}catch(IllegalArgumentException e){
					LogUtil.printlnEr("Invalid input, write:", e.getMessage());
				}catch(Throwable e){
					e.printStackTrace();
				}
			}
		});
	}
	
	//for i 0 10 write lol%i ay{1234}*%i
	public static void doCommand(IFileSystem<String> fil, UnsafeRunnable<IOException> snapshot, String cmdLine) throws IOException{
		if(cmdLine.startsWith("for ")){
			var reader=new Scanner(new ByteArrayInputStream((cmdLine+"\n").getBytes()));
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
		
		StringBuilder trimmingCommand=new StringBuilder(cmdLine);
		
		String commandName=readUntilWhitespace(trimmingCommand, "<command> ...").toUpperCase();
		
		Command command;
		try{
			command=Command.valueOf(commandName);
		}catch(IllegalArgumentException e){
			LogUtil.printlnEr("Command", commandName, "does not exist ("+Arrays.stream(Command.values()).map(Objects::toString).map(String::toLowerCase).collect(Collectors.joining(", "))+")");
			return;
		}
		
		command.executor.run(fil, trimmingCommand);
		snapshot.run();
	}
	
}

package com.lapissea.fuzz;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface FuzzLogger{
	
	final class JoiningLog implements FuzzLogger{
		
		private final List<FuzzLogger> children;
		public JoiningLog(FuzzLogger... children){ this.children = List.of(children); }
		
		@Override
		public void log(LogMessage message){
			for(var child : children){
				child.log(message);
			}
		}
	}
	
	final class LogCSV implements FuzzLogger{
		
		private final File   file;
		private       Writer writer;
		
		public LogCSV(String path){
			this(new File(path));
		}
		public LogCSV(File file){
			this.file = file;
		}
		
		
		@Override
		public void log(LogMessage message){
			try{
				switch(message){
					case LogMessage.CustomMessage customMessage -> {
						line(
							"", "",
							"", "", "",
							"", "", "", "",
							customMessage.message()
						);
					}
					case LogMessage.End ignore -> {
						writer.close();
					}
					case LogMessage.Start ignore -> {
						writer = new BufferedWriter(new FileWriter(file));
						line(
							"State", "Progress",
							"Estimated time", "Estimated time remaining", "elapsed",
							"Estimated time ms", "Estimated time remaining ms", "elapsed ms", "ns/op",
							"extra"
						);
					}
					case LogMessage.State state -> {
						line(
							state.hasFail()? "Fail" : "Ok", state.progress(),
							stdTime(state.estimatedTotalTime()), stdTime(state.estimatedTimeRemaining()), stdTime(state.elapsed()),
							optMs(state.estimatedTotalTime()), optMs(state.estimatedTimeRemaining()), state.elapsed().toMillis(), state.nanosecondsPerOp().orElse(0),
							""
						);
					}
				}
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}
		
		private Object optMs(Optional<Duration> duration){
			return duration.<Object>map(d -> FuzzTime.durationToBigDecimal(d).divide(BigInteger.valueOf(1000_000))).orElse("");
		}
		
		private void line(Object... line) throws IOException{
			String[] strs = new String[line.length];
			for(int i = 0; i<line.length; i++){
				var     s     = String.valueOf(line[i]);
				boolean quote = s.contains("\"");
				if(quote) s = s.replace("\"", "\"\"");
				if(quote || s.contains("\n") || s.contains("\r") || s.contains(",")) s = '"' + s + '"';
				strs[i] = s;
			}
			line(String.join(",", strs));
		}
		private void line(String line) throws IOException{
			writer.write(line);
			writer.write('\n');
		}
	}
	
	FuzzLogger TO_CONSOLE = msg -> {
		switch(msg){
			case LogMessage.State state -> {
				final String red   = "\033[0;91m";
				final String green = "\033[0;92m";
				
				final String gray  = "\033[0;90m";
				final String reset = "\033[0m";
				
				var    fRaw = String.format("%.1f", state.progress()*100);
				String f;
				if(fRaw.equals("100.0")) f = green + " 100%" + reset;
				else{
					f = (fRaw.length()<4? " " + fRaw : fRaw) + "%";
				}
				
				var    perOpO = state.nanosecondsPerOp();
				String unit;
				String value;
				if(perOpO.isEmpty()){
					unit = "ms";
					value = "--";
				}else{
					var perOp = perOpO.getAsDouble();
					if(perOp>1000_000_000){
						unit = " S";
						value = String.format("~%.4f", perOp/1000_000_000D);
					}else if(perOp>1000){
						unit = "ms";
						value = String.format("~%.4f", perOp/1_000_000D);
					}else{
						unit = "Ns";
						value = String.format("~%.2f", perOp);
					}
				}
				
				System.out.println(
					(state.hasFail()? red + "FAIL " : green + "OK | ") +
					gray + "Progress: " + reset + f +
					gray + ", ET: " + reset + stdTime(state.estimatedTotalTime()) +
					gray + ", ETR: " + reset + stdTime(state.estimatedTimeRemaining()) +
					gray + ", elapsed: " + reset + stdTime(state.elapsed()) +
					gray + ", " + unit + "/op: " + reset + value
				);
			}
			case LogMessage.Start initial -> {
				System.out.println("Fuzzing of \033[0;92m" + initial.fuzzName().orElse("<unnamed task>") + "\033[0m started");
			}
			case LogMessage.CustomMessage custom -> {
				System.out.println(custom.message());
			}
			case LogMessage.End ignore -> { }
		}
	};
	
	static String stdTime(Optional<Duration> tim){
		return stdTime(tim.orElse(null));
	}
	static String stdTime(Duration tim){
		if(tim == null) return "--:--:--";
		var days = tim.toDays();
		if(days == 0){
			return String.format("%02d:%02d:%02d",
			                     tim.toHoursPart(),
			                     tim.toMinutesPart(),
			                     tim.toSecondsPart());
		}else{
			return String.format("%d:%02d:%02d:%02d",
			                     days,
			                     tim.toHoursPart(),
			                     tim.toMinutesPart(),
			                     tim.toSecondsPart());
		}
	}
	
	void log(LogMessage message);
}

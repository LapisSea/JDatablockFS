package com.lapissea.cfs.tools;


import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.MemFrame;
import com.lapissea.cfs.tools.render.G2DBackend;
import com.lapissea.util.MathUtil;
import com.lapissea.util.UtilL;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.List;
import java.util.*;
import java.util.function.IntConsumer;

import static javax.swing.SwingUtilities.invokeLater;

@SuppressWarnings("AutoBoxing")
public class Display2D extends BinaryDrawing implements DataLogger{
	
	private static class Session implements DataLogger.Session{
		private final List<CachedFrame> frames=new ArrayList<>();
		private       int               pos;
		
		private final IntConsumer onFrameChange;
		private final Runnable    setDirty;
		private       boolean     markForDeletion;
		
		private Session(Runnable setDirty, IntConsumer onFrameChange){
			this.setDirty=setDirty;
			this.onFrameChange=onFrameChange;
		}
		
		@Override
		public synchronized void log(MemFrame frame){
			frames.add(new CachedFrame(frame, new ParsedFrame(frames.size())));
			setPos(frames.size()-1);
		}
		
		@Override
		public void finish(){}
		
		@Override
		public void reset(){
			frames.clear();
			setPos(0);
			setDirty.run();
		}
		@Override
		public void delete(){
			markForDeletion=true;
		}
		public void setPos(int pos){
			if(this.pos==pos) return;
			
			this.pos=pos;
			setDirty.run();
			onFrameChange.accept(getPos());
		}
		
		public int getPos(){
			return pos;
		}
	}
	
	private final Map<String, Session> sessions      =new HashMap<>();
	private       Optional<Session>    activeSession =Optional.empty();
	private       Optional<Session>    visibleSession=Optional.empty();
	private final Frame                frame         =new Frame();
	private final Panel                pan;
	
	private G2DBackend g2dRenderer;
	
	public Display2D(){
		File f=new File("wind");
		
		var t=new Thread(()->{
			while(true){
				UtilL.sleep(1);
				watchLoop();
			}
		});
		t.setDaemon(true);
		t.start();
		
		try(var in=new BufferedReader(new FileReader(f))){
			frame.setLocation(Integer.parseInt(in.readLine()), Integer.parseInt(in.readLine()));
			frame.setSize(Integer.parseInt(in.readLine()), Integer.parseInt(in.readLine()));
		}catch(Throwable ignored){
			frame.setSize(800, 800);
			frame.setLocationRelativeTo(null);
		}
		
		pan=new Panel(){
			@Override
			public void update(Graphics g){
				paint((Graphics2D)g);
			}
			@Override
			public void paint(Graphics g){
				paint((Graphics2D)g);
			}
			
			public void paint(Graphics2D g){
				try{
					render();
				}catch(Throwable e){
					new RuntimeException("Failed to complete frame render", e).printStackTrace();
				}
				g.drawImage(g2dRenderer.getRender(), 0, 0, null);
			}
		};
		g2dRenderer=new G2DBackend(pan);
		setRenderer(g2dRenderer);
		
		frame.setLayout(new BorderLayout());
		frame.add(pan);
		
		frame.addWindowListener(
			new WindowAdapter(){
				@Override
				public void windowClosed(WindowEvent e){System.exit(0);}
			}
		);
		
		frame.addKeyListener(new KeyAdapter(){
			@Override
			public void keyPressed(KeyEvent e){
				cleanUpSessions();
				visibleSession.ifPresent(ses->{
					if(e.getKeyChar()=='a'||e.getKeyCode()==37) ses.setPos(ses.getPos()-1);
					if(e.getKeyChar()=='d'||e.getKeyCode()==39) ses.setPos(ses.getPos()+1);
					ses.setPos(MathUtil.snap(ses.getPos(), 0, ses.frames.size()-1));
					g2dRenderer.markFrameDirty();
					
					if(e.getKeyCode()==122){
						frame.dispose();
						if(frame.isUndecorated()){
							frame.setUndecorated(false);
							frame.setExtendedState(JFrame.NORMAL);
							frame.setSize(800, 800);
							frame.setVisible(true);
							
						}else{
							frame.setUndecorated(true);
							frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
							frame.setVisible(true);
						}
					}
				});
			}
		});
		
		frame.addComponentListener(new ComponentAdapter(){
			@Override
			public void componentResized(ComponentEvent e){
				if(visibleSession.isPresent()){
					calcSize(getFrame(getFramePos()).data().data().length, true);
				}
				g2dRenderer.markFrameDirty();
			}
			
			@Override
			public void componentMoved(ComponentEvent e){
				super.componentMoved(e);
				if(!frame.isUndecorated()){
					try(var in=new BufferedWriter(new FileWriter(f))){
						in.write(Integer.toString(frame.getLocation().x));
						in.newLine();
						in.write(Integer.toString(frame.getLocation().y));
						in.newLine();
						in.write(Integer.toString(frame.getSize().width));
						in.newLine();
						in.write(Integer.toString(frame.getSize().height));
						in.newLine();
					}catch(Throwable ignored){}
				}
			}
		});
		
		pan.addMouseMotionListener(new MouseMotionAdapter(){
			@Override
			public void mouseMoved(MouseEvent e){
				g2dRenderer.markFrameDirty();
			}
			
			@Override
			public void mouseDragged(MouseEvent e){
				int width=pan.getWidth();
				int x    =MathUtil.snap(e.getX(), 0, width);
				
				float val=x/(float)width;
				
				cleanUpSessions();
				visibleSession.ifPresent(ses->ses.setPos((int)(val*(ses.frames.size()-1))));
				
				g2dRenderer.markFrameDirty();
			}
		});
		
		pan.addMouseListener(new MouseAdapter(){
			@Override
			public void mouseClicked(MouseEvent e){
				cleanUpSessions();
				visibleSession.ifPresent(ses->{
					if(ses.frames.isEmpty()) return;
					ses.frames.get(ses.getPos()).data().printStackTrace();
					g2dRenderer.markFrameDirty();
				});
			}
		});
		
		frame.setVisible(true);
//		frame.createBufferStrategy(2);
	}
	private void watchLoop(){
		cleanUpSessions();
		
		if(visibleSession!=activeSession){
			visibleSession=activeSession;
			g2dRenderer.markFrameDirty();
		}
		
		if(visibleSession.isEmpty()||visibleSession.get().frames.isEmpty()) return;
		
		if(g2dRenderer.notifyDirtyFrame()){
			invokeLater(pan::repaint);
		}
	}
	
	private void cleanUpSessions(){
		sessions.values().removeIf(s->s.markForDeletion);
		activeSession.filter(s->s.markForDeletion).ifPresent(s->activeSession=sessions.values().stream().findAny());
	}
	
	
	@Override
	protected boolean isWritingFilter(){
		return false;
	}
	@Override
	protected String getFilter(){
		return "";
	}
	@Override
	protected int getFrameCount(){
		return visibleSession.map(ses->ses.frames.size()).orElse(0);
	}
	@Override
	protected CachedFrame getFrame(int index){
		return visibleSession.map(ses->ses.frames.get(index)).orElse(null);
	}
	@Override
	protected int getFramePos(){
		return visibleSession.map(ses->ses.pos).orElse(0);
	}
	
	@Override
	public DataLogger.Session getSession(String name){
		var ses=sessions.computeIfAbsent(
			name,
			nam->new Session(()->g2dRenderer.markFrameDirty(), frame->{
				this.frame.setTitle("Binary display - frame: "+frame+" @"+name);
				g2dRenderer.markFrameDirty();
			})
		);
		activeSession=Optional.of(ses);
		g2dRenderer.markFrameDirty();
		return ses;
	}
	
	@Override
	public void destroy(){
		invokeLater(()->{
			frame.setVisible(false);
			frame.dispose();
		});
		sessions.values().forEach(Session::finish);
		activeSession=Optional.empty();
		visibleSession=Optional.empty();
		sessions.clear();
	}
}

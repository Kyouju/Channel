package com.sharkhunter.channel;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.pms.PMS;
import net.pms.dlna.DLNAResource;
import net.pms.dlna.Feed;
import net.pms.dlna.virtual.VirtualFolder;
import net.pms.dlna.virtual.VirtualVideoAction;
import net.pms.formats.Format;

public class ChannelNaviX extends VirtualFolder implements ChannelScraper {
	private String url;
	private Channel parent;
	private String[] props;
	private int continues;
	private boolean contAll;
	private String[] subtitle;
	private String imdbId;
	private boolean ignoreFav;
	
	public ChannelNaviX(Channel ch,String name,String thumb,String url,String[] props,String[] sub) {
		super(name,ChannelUtil.getThumb(thumb,null,ch));
		this.url=url;
		this.props=props;
		contAll=false;
		this.subtitle=sub;
		continues=ChannelUtil.calcCont(props);
		if(continues==0)
			contAll=true;
		parent=ch;
		ignoreFav=false;
	}
	
	private boolean favFolder() {
		return !(ignoreFav||parent.noFavorite());
	}
	
	public void setIgnoreFav() {
		ignoreFav=true;
	}
	
	private String washName(String name) {
		name=name.replaceAll("\\[[^]]*\\]","");
		return name;
	}
	
	private void addMedia(String name,String nextUrl,String thumb,String proc,String type,String pp,
			DLNAResource res,String imdb) {
		if(type!=null) {
			if(pp!=null)
				nextUrl=nextUrl+pp;
			if(!ChannelUtil.empty(thumb)&&thumb.equalsIgnoreCase("default"))
				thumb=null;
			name=washName(name);
			parent.debug("url "+nextUrl+" type "+type+" processor "+proc+" name "+name);
			if(type.equalsIgnoreCase("playlist")) {
				String cn=ChannelUtil.getPropertyValue(props, "continue_name");
				String cu=ChannelUtil.getPropertyValue(props, "continue_url");
				parent.debug("cont "+continues+" name "+name);
				if(!ChannelUtil.empty(cn)) { // continue
					if(name.matches(cn)) {
						continues--;
						if((contAll||continues>0)&&(continues>Channels.ContSafetyVal)) {
							readPlx(nextUrl,res);
							return;
						}
					}
				}
				if(!ChannelUtil.empty(cu)) {
					if(nextUrl.matches(cu)) {
						continues--;
						if((contAll||continues>0)&&(continues>Channels.ContSafetyVal)) {
							readPlx(nextUrl,res);
							return;
						}
					}
				}
				res.addChild(new ChannelNaviX(parent,name,thumb,nextUrl,props,subtitle));
			}
			else if(type.equalsIgnoreCase("search")) {
				ChannelNaviXSearch sobj=new ChannelNaviXSearch(this,nextUrl);
				parent.addSearcher(nextUrl, sobj);
				res.addChild(new SearchFolder(name,sobj));
			}
			else if(type.equalsIgnoreCase("rss")) {
				int f=ChannelUtil.getFormat(type);
				if(f==-1)
					f=Format.VIDEO; // guess
				res.addChild(new Feed(name,nextUrl,f));
			}
			else {
				int f=ChannelUtil.getFormat(type);
				parent.debug("add media "+f+" name "+name+" url "+nextUrl);
				if(f==-1) 
					return;
				int asx;
				if(ChannelUtil.getProperty(props,"auto_asx"))
					asx=ChannelUtil.ASXTYPE_AUTO;
				else
					asx=ChannelUtil.ASXTYPE_NONE;
				if(ChannelUtil.empty(imdb))
					imdb=imdbId;
				if(Channels.save()) {
					ChannelPMSSaveFolder sf=new ChannelPMSSaveFolder(parent,name,nextUrl,thumb,proc,
							asx,f,this);
					sf.setImdb(imdb);
					res.addChild(sf);
				}
				else {
					ChannelMediaStream cms=new ChannelMediaStream(parent,name,nextUrl,thumb,proc,
							f,asx,this);
					cms.setImdb(imdb);
					cms.setRender(this.defaultRenderer);
					res.addChild(cms);
				}
			}
		}
	}
	
	public void readPlx(String str,DLNAResource res) {
		// The URL found in the cf points to a NaviX playlist
		// (or similar) fetch and parse
		Pattern re=Pattern.compile("imdb=([^!]+)!");
		URL urlobj=null;
		try {
			urlobj = new URL(str);
		} catch (MalformedURLException e) {
			parent.debug("navix error "+e);
			return;
		}
		String page;
		try {
			page = ChannelUtil.fetchPage(urlobj.openConnection());
		} catch (Exception e) {
			page="";
		}
		parent.debug("navix page "+page);
		String[] lines=page.split("\n");
		String name=null;
		String nextUrl=null;
		String thumb=null;
		String proc=null;
		String type=null;
		String playpath=null;
		String imdb=null;
		for(int i=0;i<lines.length;i++) {
			String line=lines[i].trim();
			if(ChannelUtil.ignoreLine(line)) { // new block
				addMedia(name,nextUrl,thumb,proc,type,playpath,res,imdb);
				name=null;
				nextUrl=null;
				thumb=null;
				proc=null;
				type=null;
				playpath=null;
				imdb=null;
				continue;
			}
			if(line.startsWith("URL="))
				nextUrl=line.substring(4);
			else if(line.startsWith("name="))
				name=line.substring(5);
			else if(line.startsWith("thumb="))
				thumb=line.substring(6);
			else if(line.startsWith("processor="))
				proc=line.substring(10);
			else if(line.startsWith("type="))
				type=line.substring(5);	
			else if(line.startsWith("playpath="))
				playpath=line.substring(9);
			else if(line.startsWith("description=")) {
				int pos=line.indexOf("/description",12 );
				if(pos==-1)
					continue;
				String descr=line.substring(12,pos);
				Matcher m=re.matcher(descr);
				if(m.find())
					imdb=m.group(1);
			}
		}
		// add last item
		addMedia(name,nextUrl,thumb,proc,type,playpath,res,imdb);
	}
	
	public void discoverChildren() {
		if(favFolder()) {
			// Add bookmark action
			final ChannelNaviX cb=this;
			final String u=url;
			final String n=name;
			addChild(new VirtualVideoAction("Add to favorite",true) { //$NON-NLS-1$
				public boolean enable() {
					cb.bookmark(n,u,null);
					return true;
				}
			});
		}
		readPlx(url,this);
	}
	
	public String subCb(String realName) {
		String imdb=imdbId;
		imdbId=null; // clear this always
		if(subtitle==null||!Channels.doSubs())
			return null;
		for(int i=0;i<subtitle.length;i++) {
			ChannelSubs subs=Channels.getSubs(subtitle[i]);
			if(subs==null)
				continue;
			if(!subs.langSupported())
				continue;
			// Maybe we should mangle the name?
			String nameMangle=ChannelUtil.getPropertyValue(props, "name_mangle");
			realName=ChannelUtil.mangle(nameMangle, realName);
			parent.debug("backtracked name "+realName);
			HashMap<String,String> subName=parent.getSubMap(realName,0);
			if(!ChannelUtil.empty(imdb))
				subName.put("imdb", imdb);
			String subFile=subs.getSubs(subName);
			parent.debug("subs "+subFile);
			if(ChannelUtil.empty(subFile))
				continue;
			
			return subFile;
		}
		return null;
	}

	@Override
	public String scrape(Channel ch, String url, String processorUrl,int format,DLNAResource start
			             ,boolean noSub,String imdb) {
		imdbId=imdb;
		return ChannelNaviXProc.parse(url,processorUrl,format,(noSub?null:this),start);
	}
	
	public Channel getChannel() {
		return parent;
	}
	
	public boolean isTranscodeFolderAvailable() {
		return false;
	}
	
	public InputStream getThumbnailInputStream() {
		try {
			return downloadAndSend(thumbnailIcon,true);
		}
		catch (Exception e) {
			return super.getThumbnailInputStream();
		}
	}
	
	public String rawEntry() {
		StringBuilder sb=new StringBuilder();
		sb.append("folder {\n");
		if(!ChannelUtil.empty(url)) {
			sb.append("url=");
			sb.append(url);
			sb.append("\n");
		}
		if(!ChannelUtil.empty(name)) {
			sb.append("name=");
			sb.append(name);
			sb.append("\n");
		}
		sb.append("type=");
		sb.append("navix");
		sb.append("\n");
		if(subtitle!=null) {
			sb.append("subtitle=");
			ChannelUtil.list2file(sb,subtitle);
			sb.append("\n");
		}
		if(props!=null) {
			sb.append("prop=");
			ChannelUtil.list2file(sb,props);
			sb.append("\n");
		}
		sb.append("\n}\n");
		return sb.toString();
	}
	
	public void bookmark(String name,String url,String thumb) {
		if(!favFolder()) // weird but better safe than sorry
			return;
		StringBuilder sb=new StringBuilder();
		String data=rawEntry();
		ArrayList<String> block=ChannelUtil.gatherBlock(data.split("\n"), 1);
		ChannelFolder cf=new ChannelFolder(block,parent);
		cf.setIgnoreFav();
		parent.addFavorite(cf);
		sb.append("favorite {\n");
		sb.append("owner=");
		sb.append(parent.name());
		sb.append("\n");
		sb.append(data);
		sb.append("\n}\r\n");
		ChannelUtil.addToFavFile(sb.toString(),name);
		try {
			ChannelNaviXUpdate.updatePlx(name, url);
		} catch (Exception e) {
			Channels.debug("navix update error "+e);
		}
	}

	@Override
	public long delay() {
		return 0;
	}

	@Override
	public String backtrackedName(DLNAResource start) {
		return null;
	}
		
}

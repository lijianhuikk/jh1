package cn.rojao.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.beanutils.BeanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;

import cn.rojao.annotation.ServiceExporter;
import cn.rojao.entity.AdWebBody;
import cn.rojao.entity.AdWebEntity;
import cn.rojao.entity.AdWebHead;
import cn.rojao.entity.AdWebREQT;
import cn.rojao.entity.AdWebRESP;
import cn.rojao.entity.AdsFlag;
import cn.rojao.entity.AdvREQT;
import cn.rojao.entity.AdvRESP;
import cn.rojao.entity.Attributes;
import cn.rojao.entity.Contents;
import cn.rojao.entity.InfoREQT;
import cn.rojao.entity.InterCutBody;
import cn.rojao.entity.InterCutEntity;
import cn.rojao.entity.InterCutResp;
import cn.rojao.entity.InterCutSeq;
import cn.rojao.entity.InterCutSmil;
import cn.rojao.entity.Item;
import cn.rojao.entity.LevelEntity;
import cn.rojao.entity.ScreenInfo;
import cn.rojao.entity.SourceData;
import cn.rojao.entity.Sources;
import cn.rojao.entity.UeResponse;
import cn.rojao.entity.VideoItem;
import cn.rojao.entity.ZyWebRESP;
import cn.rojao.redis.RedisUtil;
import cn.rojao.redis.pojo.MaterialRedis;
import cn.rojao.redis.pojo.ProgramRedis;
import cn.rojao.redis.pojo.ScheduleRedis;
import cn.rojao.redis.pojo.UserRedis;
import cn.rojao.util.MessageUtil;
import cn.rojao.util.SSLClient;
import cn.rojao.util.StringUtils;

@Service("scheduleService")
@ServiceExporter(targetInterface = ScheduleService.class)
public class ScheduleService {
	
	private static final Logger logger = LoggerFactory.getLogger(ScheduleService.class);

	
	@Autowired
	RedisUtil redisUtil;
	
	@Autowired
	ScheduleUtil scheduleUtil;
	
    @Value("${upload.NGINX_PATH}")
    public String NGINX_PATH;
    

	/**
	 * 广告处理方法
	 * @param advReqt
	 * @return
	 */
	public AdvRESP dealAdv(AdvREQT advReqt){
		//判断按广告位类型请求广告还是接广告位ID
		String advId = advReqt.getAdvId();
		List<ScheduleRedis> objectList = new ArrayList<ScheduleRedis>();
		List<Object> subAdvType = new ArrayList<Object>();
		AdvRESP advRESP = null;
		
		//按类型查询符合的广告排期
		if(advId != null){
			if(advId.equals("0")){//按广告类型请求广告
				objectList = redisUtil.getSchedules(advReqt.getAdvType(), advReqt.getAdvSubType());
				subAdvType.addAll( redisUtil.subAdvTypes(advReqt.getAdvType(), advReqt.getAdvSubType()));
			}else{//按广告位外部编码请求广告
				objectList = redisUtil.getSchedules( advId );
				subAdvType.add("000");//代表外部编码
			}
		}
		//判断没有符合的广告位时返回相对应的编码
		if(objectList.size() == 0){
		    advRESP =  new AdvRESP("100000");
		    advRESP.setSessionId(advReqt.getSessionId());
		    advRESP.setResultCount(0l);
		    toReport(advReqt, advRESP.getResultCode(), advRESP.getResultDesc());
            return advRESP;
		}
		//根据广告位从redis查询出相对应的排期
		List<ScheduleRedis> scheduleList = scheduleUtil.transformObject(objectList, advReqt);
	
		//判断用户及广告位是否在黑名单中，由于接口需返回黑名单情况编码
		if(scheduleList != null && scheduleList.size() > 0){
		    scheduleList = scheduleUtil.judgeBlacklist(scheduleList,  advReqt);
		    if(scheduleList.size() == 0){
		    	advRESP =  new AdvRESP("100000");
	            advRESP.setSessionId(advReqt.getSessionId());
	            advRESP.setResultCount(0l);
	            toReport(advReqt, advRESP.getResultCode(), advRESP.getResultDesc());
	            return advRESP;
		    }
		}
		
		
		//返回符合条件的投放
		scheduleList =  scheduleUtil.judgeBySchedule(scheduleList,  advReqt );
		
		//筛选出各个广告位类型中最高级别的投放，由于channelId为-1时需返回各类型广告的最高级别
		if("-1".equals(advReqt.getChannelId())){
		    scheduleList =  getHighLevel(scheduleList);
		}
		
		//由符合的排期获取到对应的素材
		List<Item> itemList = scheduleUtil.getMaterial( advReqt , scheduleList,subAdvType);

		if(itemList != null && itemList.size()>0){
			advRESP = new AdvRESP(itemList, 0L , advReqt.getSessionId());
		}else{//没有找到合适广告
			advRESP =  new AdvRESP("100000");
			advRESP.setSessionId(advReqt.getSessionId());
            advRESP.setResultCount(0l);
            toReport(advReqt, advRESP.getResultCode(), advRESP.getResultDesc());
		}
		return advRESP;
	}
	
	/**
	 * 卓影智能推荐接口处理返回信息
	 * @param advReqt
	 * @param tempCode
	 * @return
	 */
	public ZyWebRESP getZyWebResp(AdvREQT advReqt,String tempCode){
    	ZyWebRESP zywebresp = new ZyWebRESP();
    	Contents contents = new Contents();
    	Sources sources = new Sources();
    	List<Sources> sourcesList = new ArrayList<Sources>();
    	List<Contents> contentstList = new ArrayList<Contents>();
    	zywebresp.setContentVer("0");
    	zywebresp.setTempCode(tempCode);
    	String positypeString = redisUtil.findTempletRedis(tempCode);
    	Set<String> parsestrStrings = new HashSet<String>();
    	if(StringUtils.isNotEmpty(positypeString)){
    		String advTypeString[] = positypeString.split("-");
    		parsestrStrings = redisUtil.getParsestr(advTypeString[0], advTypeString[1]);
    	}else{
    		logger.info("没有找到对应的广告类型");
    	}
    	for(String pasestr:parsestrStrings){
    		advReqt.setAdvId(pasestr);
    		AdvRESP resp = this.dealAdv(advReqt);//广告处理处理方法
        	List<Item> gz= resp.getAdvItem();
        	contents.setPostion(pasestr);
        	contents.setContentVer("0");
        	if(gz!=null&&gz.size()>0){
        		contents.setSourceTime(gz.get(0).getPositionCreateTime());
            	for(Item item : gz){
            		if(item.getAssetType()==1||item.getAssetType()==3){//只获取图片和视频的素材
            			sources.setSourceName(item.getMaterialName());
        				if(item.getAssetType()==1){//图片
        					sources.setChildType("0");
        				}else if(item.getAssetType()==3){//视频
        					sources.setChildType("1");
        				}
                    	
        				sources.setSourceType("0_0");
        				sources.setSourcePicUrl(item.getAdvURL());
        				sources.setSourceUrl(item.getHref());
        				sources.setSourceKey(item.getSourceKey());     	
        				sources.setClassName(item.getClassName());
        				sources.setPackageName(item.getPackageName());
        				sources.setRemark("null");
        				sources.setShowUrl(item.getShowUrl());
        				sources.setClickUrl(item.getClickUrl());
        				sourcesList.add(sources);
                    	sources = new Sources();
            		}
            	}
            	contents.setSources(sourcesList);
        	}
        	contentstList.add(contents);
			contents = new Contents();
			sourcesList = new ArrayList<Sources>();
    	}
    	zywebresp.setContents(contentstList);
    	return zywebresp;
	}
	
	/**
     * 将原先广告返回对象转换为主菜单接口所要求返回格式
     * @param advResp
     * @param request
     * @return
     */
    public String getUeResp(AdvREQT request,List<String> subTypes){
        List<UeResponse> lists = new ArrayList<>();
        for(String subType : subTypes){
            request.setAdvSubType(subType);
            AdvRESP advResp = dealAdv(request);
            UeResponse ueResponse = new UeResponse();
            ueResponse.setIdentity(getPosId(subType));
            ueResponse.setShowType("0");
            ueResponse.setType("1");
            List<SourceData> sourceDatas = new ArrayList<>();
            if(advResp != null){
                List<Item> items = advResp.getAdvItem();
                if(items != null && items.size() > 0){                                            
                    for(Item item : items){
                        SourceData sourceData = new SourceData();
                        sourceData.setContent(item.getContext() == null ? "" : item.getContext());
                        sourceData.setImg(item.getAdvURL());
                        sourceData.setName(item.getMaterialName());
                        sourceData.setSrc(item.getHref() == null ? "" : item.getHref());
                        sourceDatas.add(sourceData);
                    }
                }
            }
            ueResponse.setSourceData(sourceDatas);
            lists.add(ueResponse);
        }

        return lists.toString();
    }
  
    /**
     * 通过编码获取相对应的posId的值
     * @param type
     * @return
     */
    public String getPosId(String type){
        String posId = "";
        if("001".equals(type)){
            posId="index2.0_1";
        }else if("002".equals(type)){
            posId="index2.0_2";
        }else if("003".equals(type)){
            posId="index2.0_3";
        }else if("004".equals(type)){
            posId="index2.0_4";
        }else if("005".equals(type)){
            posId="index2.0_5";
        }else if("006".equals(type)){
            posId="index2.0_6";
        }
        return posId;
    }
	
    /**
	 * 截取页面请求广告接口的参数
	 * @param uri
	 * @return
	 */
    public AdvREQT getParametter(String uri){
        String parameterLine = uri.split("\\?")[1];
        String[] parameters = parameterLine.split("&");
        AdvREQT request = new AdvREQT();
        request.setAdvType("004");
        request.setSessionId("9999");
        request.setReqNum(-1l);
        request.setAdvId("0");
        //String posId = "";
        for(String parameter : parameters){
            if(parameter.indexOf("client") != -1){
                request.setClientId(parameter.replace("client=", ""));
            }else if(parameter.indexOf("areaCode") != -1){
                request.setRegionCode(parameter.replace("areaCode=", ""));
            }else if(parameter.indexOf("netWorkId") != -1){
                request.setNetworkId(parameter.replace("netWorkId=", ""));
            }else if(parameter.indexOf("posId") != -1){
                String posIdStr = parameter.replace("posId=", "");
                String[] posIds = posIdStr.split(",");
                Set<String> subTypes = new HashSet<>();
                for(String posId : posIds){
                    request.setPosId(posId);
                    String advSubType = "";
                    if("index2.0_1".equals(posId)){
                        advSubType = "001";
                    }else if("index2.0_2".equals(posId)){
                        advSubType = "002";
                    }else if("index2.0_3".equals(posId)){
                        advSubType = "003";
                    }else if("index2.0_4".equals(posId)){
                        advSubType = "004";
                    }else if("index2.0_5".equals(posId)){
                        advSubType = "005";
                    }else if("index2.0_6".equals(posId)){
                        advSubType = "006";
                    }  
                    subTypes.add(advSubType);
                }
                subTypes.remove("");
                List<String> subTypeList = new ArrayList<>();
                subTypeList.addAll(subTypes);
                request.setSubTypes(subTypeList);
            }
        }
        return request;
    }
    
    /**
     * 将AD-i5的请求参数转换为原先广告请求对象
     * @param request
     * @return
     */
    public AdvREQT gAdvREQT(AdWebREQT request){
        AdvREQT req= new AdvREQT();
        req.setClientId(request.getUserID());
        req.setProviderId(request.getProviderID());
        if(!request.getAreaID().equals("-1")){
            String regionIds = request.getAreaID();
            String[] regionId = regionIds.split("\\|");
            if(regionId.length > 0){
                req.setRegionCode(regionId[0]);
            }                                   
            if(regionId.length > 1){
                req.setNetworkId(regionId[1]);
            }
        }
        //根据观看类型对参数进行赋值，频道、内容以及栏目
        if(request.getServiceType().equals("0")){
            
            req.setChannelId(request.getRelationID());
        }else if(request.getServiceType().equals("1")){
            
            req.setCatagoryId(request.getRelationID());
            req.setContentId(request.getAssetID());
            req.setFolderContentId(request.getAssetID());
        }else if(request.getServiceType().equals("2")){
            
            req.setChannelId(request.getRelationID());
            req.setContentId(request.getAssetID());
            req.setFolderContentId(request.getAssetID());
        }else if(request.getServiceType().equals("3")){
            
            req.setChannelId(request.getRelationID());
        }
        return req;
    }
    
    /**
     * AD-i5将返回的广告对象处理成所需的返回格式
     * @param advResp
     * @param positionId
     * @return
     */
    public List<AdWebBody> gAdWebBodies(AdvRESP advResp,String positionId){
        List<AdWebBody> bodies = new ArrayList<>();
        if(advResp.getAdvItem() != null && advResp.getAdvItem().size() > 0){
            List<Item> items = advResp.getAdvItem();
            Map<String, List<Item>> map = classifyItems(items);
            Set<String> keys = map.keySet();
            for(String key : keys){
                List<Item> list = map.get(key);
                
                AdWebBody adWebBody = new AdWebBody();
                adWebBody.setAdpID(positionId);
                
                List<AdWebEntity> entities = new ArrayList<>();//广告素材对象集合
                int i = 1;
                Long scheduleId = 0l;
                for(Item item : list){
                    //过滤掉广告素材中的非图片、视频素材
                    if(item.getAssetType().longValue() == 1l || item.getAssetType().longValue() == 7l
                            || item.getAssetType().longValue() == 3l){                                                
                        AdWebEntity entity = new AdWebEntity(item);
                        entity.setSequence(i);
                        entities.add(entity);
                        scheduleId = item.getScheduleId();
                        i++;                                                
                    }
                }
                //判断素材对象集合中有没有图片素材
                if(entities.size() > 0){
                    adWebBody.setAdWebList(entities);
                    adWebBody.setScheduleId(scheduleId.toString());//设置投放ID
                    adWebBody.setResultCode("0");                                            
                }else{
                    logger.debug("请求到广告，但是广告素材中不含有图片、视频");
                    adWebBody.setResultCode("703");
                }
                bodies.add(adWebBody);
            }
        }else{
            AdWebBody adWebBody = new AdWebBody();
            adWebBody.setAdpID(positionId);
            if("100003".equals(advResp.getResultCode())){
                adWebBody.setResultCode("714");
            }else if("100000".equals(advResp.getResultCode())){
                adWebBody.setResultCode("703");
            }else{
                adWebBody.setResultCode("703");
            }
            bodies.add(adWebBody);
        }
        
        return bodies;
    }
    
    /**
     * 将i4视频请求接口地址截取参数生成请求参数体
     * @param uri
     * @return
     */
    public InterCutEntity gInterCutEntity(String uri){
        InterCutEntity interCutEntity = new InterCutEntity();
        //assetId=cpuserfdgdfgdfe3333232323&providerId=11111111&sno=sno123456789&catalogId=001&areaCode=101&businessType=0&streamRate=0&clientType=1
        String[] parameters = uri.split("&");
        for(String parameter : parameters){
            String value = "";
            if(parameter.indexOf("=")>-1){
                value = parameter.substring(parameter.indexOf("=")+1,parameter.length());
            }
            if(parameter.indexOf("assetId")>-1){
                interCutEntity.setAssetId(value);
            }else if(parameter.indexOf("providerId")>-1){
                interCutEntity.setProviderId(value);
            }else if(parameter.indexOf("sno")>-1){
                interCutEntity.setSno(value);
            }else if(parameter.indexOf("catalogId")>-1){
                interCutEntity.setCatalogId(value);
            }else if(parameter.indexOf("areaCode")>-1){
                interCutEntity.setAreaCode(value);
            }else if(parameter.indexOf("businessType")>-1){
                interCutEntity.setBusinessType(value);
            }else if(parameter.indexOf("streamRate")>-1){
                interCutEntity.setStreamRate(value);
            }else if(parameter.indexOf("clientType")>-1){
                interCutEntity.setClientType(value);
            }
        }
        
        return interCutEntity;
    }
    
    
    /**
     * 将i4请求参数转换为原广告接口的请求参数体
     * @param interCut
     * @return
     */
    public AdvREQT changeIntCutReq(InterCutEntity interCut){
        AdvREQT request = new AdvREQT();
        request.setContentId(interCut.getAssetId());
        request.setFolderContentId(interCut.getAssetId());
        request.setProviderId(interCut.getProviderId());
        request.setClientId(interCut.getSno());
        if("0".equals(interCut.getBusinessType())){
            request.setCatagoryId(interCut.getCatalogId());
        }else{
            request.setChannelId(interCut.getCatalogId());
        }
        if(interCut.getStreamRate()!=null){
            if("0".equals(interCut.getStreamRate())){
                request.setFormat("2"); 
            }else if("1".equals(interCut.getStreamRate())){
                request.setFormat("1"); 
            }else if("2".equals(interCut.getStreamRate())){
                request.setFormat("3"); 
            }else{
                request.setFormat("2");
            }
        }
        if(!interCut.getAreaCode().equals("-1")){
            String regionIds = interCut.getAreaCode();
            String[] regionId = regionIds.split("\\|");
            if(regionId.length > 0){
                request.setRegionCode(regionId[0]);
            }                                   
            if(regionId.length > 1){
                request.setNetworkId(regionId[1]);
            }
        }
        //request.setRegionCode(interCut.getAreaCode());
        request.setAdvType("001");
        request.setReqNum(-1l);
        request.setAdvId("0");
        
        return request;
    }
    /**
     * 将原先广告接口请求到的素材列表Item转换成i4视频广告请求接口返回的格式
     * @param items
     * @param interCut
     * @return
     */
    public InterCutResp getInterCutResp(List<Item> items,InterCutEntity interCut){
        InterCutResp resp = new InterCutResp();
        List<VideoItem> video = new ArrayList<VideoItem>();
        List<Long> bitRates = new ArrayList<Long>();
        for(Item item : items){
            Attributes attribute = new Attributes(item,interCut);
            VideoItem videoItem = new VideoItem();
            videoItem.setAttributes(attribute);
            video.add(videoItem);
            if(StringUtils.isNotEmpty(attribute.getBitRate())){
                bitRates.add(Long.parseLong(attribute.getBitRate().replace("kb/s", "").trim()));
            }
        }
        if(video.size() > 0){
            InterCutSeq seq = new InterCutSeq();
            seq.setVideo(video);
            if(bitRates.size() > 0){
                seq.setMaxBitRate(Collections.max(bitRates).toString());
            }else{
                seq.setMaxBitRate("");
            }
            InterCutBody body = new InterCutBody();
            body.setSeq(seq);
            InterCutSmil smil = new InterCutSmil();
            smil.setBody(body);
            smil.setHead("");
            resp.setSmil(smil);
            resp.setMessage("");
            resp.setRetcode("0");           
        }else{
            resp.setRetcode("100000");
            resp.setMessage("no Suitable adv");            
        }
        return resp;
    }
    /**
     * 筛选出各个类型的广告位的最高级别排期
     * @param list
     * @return
     */
    public List<ScheduleRedis> getHighLevel(List<ScheduleRedis> list){
        Map<String, Map<String,List<ScheduleRedis>>> map = new HashMap<>();
        List<ScheduleRedis> scheduleList = new ArrayList<>();
        for(ScheduleRedis schedule : list){
            String type = schedule.getAdvType() + "_" + schedule.getAdvSonType();
            Map<String,List<ScheduleRedis>> sMap = new HashMap<>();
            if(map.get(type) != null){
                sMap = map.get(type);
            }
            List<ScheduleRedis> scheduleRedis = new ArrayList<>();
            String level = schedule.getPriority().toString();
            if(sMap.get(level) != null){
                scheduleRedis = sMap.get(level);
            }
            scheduleRedis.add(schedule);
            sMap.put(level, scheduleRedis);
            map.put(type, sMap);
        }
        Set<String> typeKeys = map.keySet();
        if(typeKeys!=null && typeKeys.size()>0){
            for(String typeKey : typeKeys){
                Map<String,List<ScheduleRedis>> levelSchedule = map.get(typeKey);
                Set<String> levelSets = levelSchedule.keySet();
                String highest = Collections.max(levelSets);
                if(StringUtils.isNotEmpty(highest)){
                    scheduleList.addAll(levelSchedule.get(highest));
                }
            }
        }
        return scheduleList;
    }
    
    
    
    /**
     * 将获取的广告素材按广告位子类型进行分类
     * @param items
     * @return
     */
    public Map<String, List<Item>> classifyItems(List<Item> items){
        Map<String, List<Item>> map = new TreeMap<String, List<Item>>();
        for(Item item : items){
            String subType = item.getAdvSubType();
            if(!map.containsKey(subType)){
                List<Item> list = new ArrayList<>();
                map.put(subType, list);                
            }
        }         
        for(Item item : items){
            String subType = item.getAdvSubType();
            List<Item> list = map.get(subType);
            list.add(item);
        }
        return map;
    }

    /**
     * 分发未授权时各个接口返回内容格式
     * @param uri
     * @return
     */
    public String authorizeMsg(String uri){
        String msg = "";
        if(uri.endsWith("GetAdvApp")){
            AdvRESP advRESP =  new AdvRESP("111111");
            advRESP.setSessionId("");
            advRESP.setResultCount(0L);
            msg =  advRESP.build();            
        }else if(uri.contains("adUePut")){
            AdvRESP advRESP =  new AdvRESP("111111");
            msg = advRESP.build();            
        }else if(uri.endsWith("getPageAdv")){
            AdWebRESP res = new AdWebRESP();
            AdWebHead head = new AdWebHead();
            head.setVersion("2.0");
            res.setHead(head);            
            List<AdWebBody> bodies = new ArrayList<>();
            AdWebBody adWebBody = new AdWebBody();
            adWebBody.setResultCode("111111");
            adWebBody.setAdpID("");
            bodies.add(adWebBody);
            res.setBody(bodies);
            msg = res.toString();
        }else if(uri.contains("assetId")){
            InterCutResp resp = new InterCutResp();
            resp.setRetcode("111111");
            resp.setMessage("The system is not authorized");
            msg = resp.toString();            
        }
        return msg;
    }
	
    /**
     * 当adpID=TYPE_012或者TYPE_013时，adWebList的排序为列出所有可用的视频广告后，才列出图片广告
     * @param entities
     * @param positionId
     */
    public void entitySort(List<AdWebEntity> entities,String positionId){
        if(positionId.equals("TYPE_012") || positionId.equals("TYPE_013")){
            Collections.sort(entities);
            int i = 1;
            for(AdWebEntity entity : entities){
                entity.setSequence(i);
                i++;
            }
        }
    }
    
    public ScreenInfo dealScreen(InfoREQT req){
    	ScreenInfo info = new ScreenInfo();
    	String nginxPath = redisUtil.getSysParameter("nginxPath");
    	String screenUrl = redisUtil.getSysParameter("screenUrl");
    	String screenTime = redisUtil.getSysParameter("screenTime");
    	String pageUrl = "";
    	if(StringUtils.isNotEmpty(nginxPath) && StringUtils.isNotEmpty(screenUrl)){
    		//String param = dealPara(req);
    		pageUrl = nginxPath + "/" + screenUrl;
    	}
    	info.setSessionId(req.getSessionId());
    	info.setPageUrl(pageUrl);
    	if(StringUtils.isEmpty(screenTime)){
    		screenTime = "180";
    	}
    	info.setScreenTime(screenTime);
    	return info;
    }
    
    public String dealPara(InfoREQT req){
    	if(req != null){
    		StringBuilder params = new StringBuilder();
    		params.append("?");
    		if(StringUtils.isNotEmpty(req.getSessionId())){
    			params.append("sessionId=" + req.getSessionId() + "&");
    		}
    		if(StringUtils.isNotEmpty(req.getInfoId())){
    			params.append("infoId=" + req.getInfoId() + "&");
    		}
    		if(StringUtils.isNotEmpty(req.getProviderId())){
    			params.append("providerId=" + req.getProviderId() + "&");
    		}
    		if(StringUtils.isNotEmpty(req.getContentId())){
    			params.append("contentId=" + req.getContentId() + "&");
    		}
    		if(StringUtils.isNotEmpty(req.getFormat())){
    			params.append("format=" + req.getFormat() + "&");
    		}
    		if(StringUtils.isNotEmpty(req.getFolderContentId())){
    			params.append("folderContentId=" + req.getFolderContentId() + "&");
    		}
    		if(StringUtils.isNotEmpty(req.getCatagoryId())){
    			params.append("catagoryId=" + req.getCatagoryId() + "&");
    		}
    		if(StringUtils.isNotEmpty(req.getChannelId())){
    			params.append("channelId=" + req.getChannelId() + "&");
    		}
    		if(StringUtils.isNotEmpty(req.getClientId())){
    			params.append("clientId=" + req.getClientId() + "&");
    		}
    		if(StringUtils.isNotEmpty(req.getDeviceType())){
    			params.append("deviceType=" + req.getDeviceType() + "&");
    		}
    		if(StringUtils.isNotEmpty(req.getUrl())){
    			params.append("url=" + req.getUrl() + "&");
    		}
    		String param = params.toString();
    		param = param.substring(0, param.length()-1);
    		return param;
    	}
    	return "";
    }
    
    /**
     * 获取到管理端的aseKey值
     * @return
     */
    public String getAseKey(){
    	String aesKey = redisUtil.getSysParameter("aesKey");
    	if(StringUtils.isNotEmpty(aesKey)){
    		return aesKey;
    	}
    	return "";
    }
    
	/**
	 * 获取不到广告时上报
	 * @param schedule
	 * @param reportItems
	 * @param reportUrl
	 */
	public void toReport(AdvREQT request,String errorCode,String errorDesc){
		try {
			
			String reportUrl = redisUtil.getSysParameter("reportUrl");
			
	        String deviceCode = request.getDeviceCode() == null ? "" : request.getDeviceCode();
	        String deviceType = request.getDeviceType() == null ? "" : request.getDeviceType();
	        String networkId = request.getNetworkId() == null ? "" : request.getNetworkId();
	        String regionCode = request.getRegionCode() == null ? "" : request.getRegionCode();
			
			String advId = request.getAdvId() == null ? "" : request.getAdvId();
			String providerId = request.getProviderId() == null ? "" : request.getProviderId();
			String catagory = request.getCatagoryId() == null ? "" : request.getCatagoryId();
			String contentId = request.getContentId() == null ? "" : request.getContentId();
			String folderContentId = request.getFolderContentId() == null ? "" : request.getFolderContentId();
			String channelId = request.getChannelId() == null ? "" : request.getChannelId();
			
			String param = deviceCode + "|" + deviceType + "|" + networkId + "|" + regionCode + "|0x22|"
					+ advId + "^" + providerId + "^" + catagory + "^" + contentId + "^" + folderContentId 
					+ "^" + channelId + "^" + errorCode + "^" + errorDesc;
			String reportPath = reportUrl + "?" + param;
			logger.debug("reportPath====" + reportPath);
			SSLClient.sendGet(reportPath, "");
		} catch (Exception e) {
			logger.error("report faild");
		}
		
	}
    
}

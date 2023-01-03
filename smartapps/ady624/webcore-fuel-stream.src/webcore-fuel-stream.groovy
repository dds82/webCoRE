/*
 *  webCoRE - Community's own Rule Engine - Web Edition for HE
 *
 *  Copyright 2016 Adrian Caramaliu <ady624("at" sign goes here)gmail.com>
 *
 *  webCoRE Fuel Stream & graphs
 *
 *
 *  Significant parts of graphs modified from Hubigraph by tchoward
 *
 *  Copyright 2020, but let's be honest, you'll copy it
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *	http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Last update January 2, 2023 for Hubitat
 */

//file:noinspection GroovySillyAssignment
//file:noinspection GrDeprecatedAPIUsage
//file:noinspection GroovyDoubleNegation
//file:noinspection GroovyUnusedAssignment
//file:noinspection unused
//file:noinspection GroovyAssignabilityCheck
//file:noinspection SpellCheckingInspection
//file:noinspection GroovyFallthrough
//file:noinspection GrMethodMayBeStatic

import groovy.json.*
import groovy.time.TimeCategory

import java.text.DecimalFormat
import groovy.transform.Field
import groovy.transform.CompileStatic
import java.util.concurrent.Semaphore

private static String handle(){ return "webCoRE" }
@Field static final String sVER='v0.3.114.20220203'
@Field static final String sHVER='v0.3.114.20220714_HE'
public static String version(){ return sVER }
public static String HEversion(){ return sHVER }


@CompileStatic
static Boolean eric(){ return false }
@CompileStatic
static Boolean eric1(){ return false }
@CompileStatic
private Boolean isEric(){ eric1() && isDbg() }

private Boolean isSystemType(){
	if (!eric()) return isSystemTypeOrHubDeveloper()
	return false
}

definition(
	namespace:"ady624",
	(sNM):"${handle()} Fuel Stream",
	description: "Local container for fuel streams, graphs",
	author:"jp0550",
	category:"My Apps",
	iconUrl:gimg('app-CoRE.png'),
	iconX2Url:gimg('app-CoRE@2x.png'),
	iconX3Url:gimg('app-CoRE@3x.png'),
	importUrl:'https://raw.githubusercontent.com/imnotbob/webCoRE/hubitat-patches/smartapps/ady624/webcore-fuel-stream.src/webcore-fuel-stream.groovy',
	parent: "ady624:${handle()}"
)

@Field static final String sBOOL='bool'
@Field static final String sTEXT='text'
@Field static final String sATTR='attribute'
@Field static final String sDISPNM='displayName'
@Field static final String sDT='date'
@Field static final String sGRAPHT='graphType'
@Field static final String sLONGTS='longtermstorage'

preferences{
	page(name: "mainPage", install: true, uninstall: true)
	page(name: "deviceSelectionPage")
	page(name: "attributeConfigurationPage", nextPage: "mainPage")
	page(name: "graphSetupPage", nextPage: "mainPage")
	page(name: "enableAPIPage")
	page(name: "disableAPIPage")
}

mappings{
	path("/graph/"){ action: [ GET: "getGraph" ] }
	path("/getData/"){ action: [ GET: "getData" ] }
	path("/getOptions/"){ action: [ GET: "getOptions" ] }
	path("/getSubscriptions/"){ action: [ GET: "getSubscriptions" ] }
	path("/updateSettings/"){ action: [ POST: "updateSettings" ] }
	path("/tile/"){ action: [ GET: "getTile" ] }
}

def installed(){
	log.debug "Installed with settings: ${settings}"
	state[sDBGLVL]=iZ
	state[sLOGNG]=iZ
	if((Boolean)settings.duplicateFlag && !(Boolean)state.dupPendingSetup){
		Boolean maybeDup= ((String)app?.getLabel())?.contains(' (Dup)')
		state.dupPendingSetup= true
		runIn(2, "processDuplication")
		if(maybeDup) info "installed found maybe a dup... ${(Boolean)settings.duplicateFlag}",null
	}else if(!(Boolean)state.dupPendingSetup){
		if(app_name) app.updateLabel(app_name)
	}
}

private void processDuplication(){
	String al= (String)app?.getLabel()
	String newLbl= "${al}${al?.contains(' (Dup)') ? "" : ' (Dup)'}"
	app?.updateLabel(newLbl)
	state.dupPendingSetup= true

	String dupSrcId= settings.duplicateSrcId ? sMs(settings,'duplicateSrcId') : sNL
	Map dupData= parent?.getChildDupeData("graphs", dupSrcId)
	if(eric()) log.debug "dupData: ${dupData}"
	Map dd
	dd= dupData?.state
	if(dupData && dd?.size()){
		dd.each{ String k,v-> state[k]= v }
	}

	dd= dupData?.settings
	if(dupData && dd?.size()){
		dd.each{ String k, Map v->
			if(sMs(v,sTYPE) in [sENUM, 'mode']){
				wremoveSetting(k)
				settingUpdate(k, (v[sVAL] != null ? v[sVAL] : null), sMs(v,sTYPE))
			}
		}
	}

	parent.childAppDuplicationFinished("graphs", dupSrcId)
	info "Duplicated Graph has been created... Please open the new graph and configure to complete setup...",null
}

def uninstalled(){
	if(state.endpoint){
		try{
			log.debug "Revoking API access token"
			revokeAccessToken()
		}catch(e){
			warn "Unable to revoke API access token: ",null,iN2,e
		}
	}
	removeChildDevices(getChildDevices())

	Map foo=(Map)state.fuelStream
	if(foo){
		parent.resetFuelStreamList()
		fuelFLD=null
		readTmpFLD= [:]
	}
}

private removeChildDevices(delete){
	delete.each{deleteChildDevice(it.deviceNetworkId)}
}

@Field static final String dupMSGFLD= "This graph is duplicated and has not had configuration completed... Please open graph and configure to complete setup..."

def updated(){
	log.debug "updated() with settings: ${settings}"

	Boolean maybeDup= ((String)app?.getLabel())?.contains(' (Dup)')
	if(maybeDup) info "updated found maybe a dup... ${(Boolean)settings.duplicateFlag}",null
	if((Boolean)settings.duplicateFlag){
		if((Boolean)state.dupOpenedByUser){ state.dupPendingSetup= false }
		if((Boolean)state.dupPendingSetup){
			info dupMSGFLD,null
			return
		}
		info "removing duplicate status",null
		wremoveSetting('duplicateFlag'); wremoveSetting('duplicateSrcId')
		state.remove('dupOpenedByUser'); state.remove('dupPendingSetup'); state.remove('badMode')
	}

	wremoveSetting('debug')
	wremoveSetting('dummy')

	Map fs=state.fuelStream
	String typ
	typ= fs ? 'fuelstream' : sMs(settings,sGRAPHT)
	if(typ && typ!='fuelstream' && (!app_name || typ==sLONGTS)){
		app.updateSetting('app_name', 'webCoRE '+tDesc()) // cannot rename LTS
	}

	if(typ && (typ in ['fuelstream',sLONGTS])){
		readTmpFLD= [:] // clear memory file cache
		fuelFLD=null // clear list of fuel streams cache
	}

	if(app_name) app.updateLabel(app_name)

	state[sDBGLVL]=iZ
	String tt1=(String)gtSetting(sLOGNG)
	Integer tt2=iMs(state,sLOGNG)
	String tt3=tt2.toString()
	if(tt1==sNL)setLoggingLevel(tt2 ? tt3:s0)
	else if(tt1!=tt3)setLoggingLevel(tt1)

	state.remove('saveC')
	state.remove('devInstruct')

	if(install_device== true){
		hubiTool_create_tile()
	}

	if(fs){ // is a fuel stream
		if(app.id){ // if someone changed storage settings
			List<Map> a=getFuelStreamData(null, false)
			if(a) storeFuelUpdate(a,fs)
		}
	}

	if(typ==sLONGTS){
		if(isDbg()) myDetail null,"updated",i1
		unschedule()
		clearSch()
		clearSema()
		if(sensors){

			for(sensor in (List)sensors){
				String sid=gtSensorId(sensor)
				List<String> att=(List<String>)settings["${sid}_attributes"]
				if(att){
					for(String attribute in att){
						Map data=[(sID): sid, (sATTR): attribute]
						updateData_LTS(data)
						setupCron(sensor, attribute)
					}
				}
			}

			runNextSched()
			schedule("17 9/30 * ? * * *", checkSched, [overwrite: false]) // watchDog for lts
		}
		if(isDbg()) myDetail null,"updated"
	}
}

void setLoggingLevel(String level){
	Integer mlogging
	mlogging=level.isInteger()? level.toInteger():iZ
	mlogging=Math.min(Math.max(iZ,mlogging),i3)
	app.updateSetting(sLOGNG,[(sTYPE):sENUM,(sVAL):mlogging.toString()])
	state[sLOGNG]=mlogging
//	if(mlogging==iZ)state[sLOGS]=[]
}


@Field static final Map<String,Map<String,String>> jumpFLD=[
	"gauge":[
		main: "mainGauge",
		deviceSelection: "deviceGauge",
		attributeConfiguration: "attributeGauge",
		graphSetup: "graphGauge",
		getGraph: "getGraph_gauge",
		getData: "getData_gauge",
		getOptions: "getOptions_gauge",
		getSubscriptions: "getSubscriptions_gauge",
		desc: "Gauge"
	],
	"bar":[
		main: "mainBar",
		deviceSelection: "deviceBar",
		attributeConfiguration: "attributeBar",
		graphSetup: "graphBar",
		getGraph: "getGraph_bar",
		getData: "getData_bar",
		getOptions: "getOptions_bar",
		getSubscriptions: "getSubscriptions_bar",
		desc: "Bar Graph"
	],
	"timeline":[
		main: "mainTimeline",
		deviceSelection: "deviceTimeline",
		attributeConfiguration: "attributeTimeline",
		graphSetup: "graphTimeline",
		getGraph: "getGraph_timeline",
		getData: "getData_timeline",
		getOptions: "getOptions_timeline",
		getSubscriptions: "getSubscriptions_timeline",
		desc: "Time Line Chart"
	],
	"timegraph":[
		main: "mainTimegraph",
		deviceSelection: "deviceTimegraph",
		attributeConfiguration: "attributeTimegraph",
		graphSetup: "graphTimegraph",
		getGraph: "getGraph_timegraph",
		getData: "getData_timegraph",
		getOptions: "getOptions_timegraph",
		getSubscriptions: "getSubscriptions_timegraph",
		desc: "Time Graph"
	],
	"heatmap":[
		main: "mainHeatmap",
		deviceSelection: "deviceHeatmap",
		attributeConfiguration: "attributeHeatmap",
		graphSetup: "graphHeatmap",
		getGraph: "getGraph_heatmap",
		getData: "getData_heatmap",
		getOptions: "getOptions_heatmap",
		getSubscriptions: "getSubscriptions_heatmap",
		desc: "Heat Map"
	],
	"linegraph":[
		main: "mainLinegraph",
		deviceSelection: "deviceLinegraph",
		attributeConfiguration: "attributeLinegraph",
		graphSetup: "graphLinegraph",
		getGraph: "getGraph_linegraph",
		getData: "getData_linegraph",
		getOptions: "getOptions_linegraph",
		getSubscriptions: "getSubscriptions_linegraph",
		desc: "Line Graph"
	],
	"rangebar":[
		main: "mainRangebar",
		deviceSelection: "deviceRangebar",
		attributeConfiguration: "attributeRangebar",
		graphSetup: "graphRangebar",
		getGraph: "getGraph_rangebar",
		getData: "getData_rangebar",
		getOptions: "getOptions_rangebar",
		getSubscriptions: "getSubscriptions_rangebar",
		desc: "Range Bar"
	],
	"radar":[
		main: "mainRadar",
		deviceSelection: "none",
		attributeConfiguration: "none",
		graphSetup: "tileRadar",
		getGraph: "getGraph_radar",
		getData: "none",
		getOptions: "none",
		getSubscriptions: "none",
		desc: "Radar Tile"
	],
	"weather2":[
		main: "mainWeather2",
		deviceSelection: "deviceWeather2",
		attributeConfiguration: "none",
		graphSetup: "tileWeather2",
		getGraph: "getGraph_weather2",
		getData: "getData_weather2",
		getOptions: "getOptions_weather2",
		getSubscriptions: "none",
		updateSettings: "updateSettings_weather2",
		getTile: "getTile_weather2",
		desc: "Weather Tile 2.0"
	],
	"forecast":[
		main: "mainForecast",
		deviceSelection: "none",
		attributeConfiguration: "none",
		graphSetup: "tileForecast",
		getGraph: "getGraph_forecast",
		getData: "getData_forecast",
		getOptions: "getOptions_forecast",
		getSubscriptions: "none",
		desc: "Weather Forecast Tile"
	],
	"longtermstorage":[
		main: "mainLongtermstorage",
		deviceSelection: "deviceLongtermstorage",
		attributeConfiguration: "optionsLongtermstorage",
		graphSetup: "graphLongtermstorage",
		getGraph: "none",
		getData: "none",
		getOptions: "none",
		getSubscriptions: "none",
		desc: "Long Term Storage"
	],
	"fuelstream":[
		main: "mainFuelstream",
		desc: "Fuel Stream"
	],
]

String tDesc(){
	String typ=sMs(settings,sGRAPHT)
	if(typ) return sMs(jumpFLD[typ],'desc')
	return sNL
}


def checkDup(){
	Boolean dup= ((Boolean)settings.duplicateFlag && (Boolean)state.dupPendingSetup)
	if(dup){
		state.dupOpenedByUser= true
		section(){ paragraph "This Graph was created from an existing graph.<br><br>Please review the settings and save to activate...<br>${state.badMode ?: sBLK}" }
	}
}

def mainPage(){
	Map fs=(Map)state?.fuelStream
	String typ
	// fuel stream does not have graphType set
	typ= fs ? 'fuelstream' : sMs(settings,sGRAPHT)
	if(typ){
		String s= sMs(jumpFLD[typ],'main')
		if(isEric())myDetail null,"${s}:",iN2
		"${s}"()
	}
	else{
		Map stuff
		stuff=[:]

		for(par in jumpFLD){
			if(par.key in ['fuelstream']) continue // don't create fuels this way
			if(par.key in [sLONGTS]){
				Boolean ltsExists=(Boolean)parent.ltsExists()
				if(ltsExists) continue // can only be 1 LTS
			}
			stuff += [(par.key): par.value.desc]
		}
		dynamicPage(name: "mainPage"){
			section(){
				input sGRAPHT,sENUM,(sTIT):'Graph Type',options:stuff,required:true,submitOnChange:true
			}
		}
	}
}

def doJump(String meth){
	String typ=sMs(settings,sGRAPHT)
	String s= sMs(jumpFLD[typ],meth)
	if(isEric())myDetail null,"${s}:",iN2
	"${s}"()
}


def deviceSelectionPage(){
	doJump('deviceSelection')
}

def attributeConfigurationPage(){
	doJump('attributeConfiguration')
}

def graphSetupPage(){
	doJump('graphSetup')
}



//oauth endpoints
def getGraph(){
	String typ=sMs(settings,sGRAPHT)
	String s= (String)"${sMs(jumpFLD[typ],'getGraph')}"()
	String ss= sBLK // s.replaceAll('<', '&lt;').replaceAll('>','&gt;')
	if(isEric())myDetail null,"getGraph_${typ}: $ss",iN2
	return wrender(contentType: "text/html", data: s)
}

def getData(){
	String typ=sMs(settings,sGRAPHT)
	String s= (String)"${sMs(jumpFLD[typ],'getData')}"()
	if(isEric())myDetail null,"getData_${typ}: $s",iN2
	return wrender(contentType: "text/json", data: s)
}

def getOptions(){
	String typ=sMs(settings,sGRAPHT)
	String s= JsonOutput.toJson( (Map)"${sMs(jumpFLD[typ],'getOptions')}"() )
	if(isEric())myDetail null,"getOptions_${typ}: $s",iN2
	return wrender(contentType: "text/json", data: s)
}

def getSubscriptions(){
	String typ=sMs(settings,sGRAPHT)
	String s= JsonOutput.toJson( (Map)"${sMs(jumpFLD[typ],'getSubscriptions')}"() )
	if(isEric())myDetail null,"getSubscriptions_${typ}: $s",iN2
	return wrender(contentType: "text/json", data: s)
}

def updateSettings(){
	doJump('updateSettings')
}

def getTile(){
	doJump('getTile')
}


void revokeAccessToken(){
	state.remove('accessToken')
	state.remove('endpoint')
	state.remove('endpointSecret')
	state.remove('localEndpointURL')
	state.remove('remoteEndpointURL')
}

/* shared method */
void initializeAppEndpoint(Boolean disableRetry=false){
	String accessToken; accessToken=(String)state.accessToken
	if(!state.endpoint || !accessToken){
		try{
			if(!accessToken) accessToken=createAccessToken() // this fills in state.accessToken
		} catch(e){
			debug "Error: ",null,iN2,e
		}
		if(accessToken){
			state.endpoint=getApiServerUrl()
			state.localEndpointURL=fullLocalApiServerUrl(sBLK)
			state.remoteEndpointURL=fullApiServerUrl(sBLK)
			state.endpointSecret=accessToken
		}else if(!disableRetry){
			state.accessToken=null
			enableOauth()
			initializeAppEndpoint(true)
		}else {
			error "Could not get access token"
			revokeAccessToken()
		}
	}
}

private void enableOauth(){
	Map params=[
			uri: "http://localhost:8080/app/edit/update?_action_update=Update&oauthEnabled=true&id=${app.appTypeId}".toString(),
			headers: ['Content-Type':'text/html;charset=utf-8']
	]
	try{
		httpPost(params){ resp ->
			//LogTrace("response (sDATA): ${resp.data}")
		}
	}catch(e){
		error "enableOauth something went wrong: ",null,iN2,e
	}
}

def enableAPIPage(){
	dynamicPage(name: "enableAPIPage",(sTIT): sBLK){
		section(){
			if(!state.endpoint) initializeAppEndpoint()
			if(!state.endpoint){
				paragraph "Endpoint creation failed"
			}else{
				paragraph "It has been done. Your token has been CREATED. Tap Done to continue."
			}
		}
	}
}

def disableAPIPage(){
	dynamicPage(name: "disableAPIPage"){
		section(){
			if(state.endpoint){
				try{
					revokeAccessToken()
				}catch(ignored){}
			}
			paragraph "It has been done. Your token has been REVOKED. Tap Done to continue."
		}
	}
}


/** m.string  */
@CompileStatic
private static String sMs(Map m,String v){ (String)m[v] }

/** m.string  */
@CompileStatic
private static Integer iMs(Map m,String v){ (Integer)m[v] }






@Field static final String sFuelDelim='-'
/**
 * Encode a stream identifier Map to settings String
 * @param stream [i:app.id, c: 'LTS', n:sid+'_'+attribute,w:1,t: getFormattedDate(new Date())]
 * @return id-canister||name
 */
@CompileStatic
static String encodeStreamN(Map stream){
	String streamName="${(stream.c ?: sBLK)}||${stream.n}"
	String id="${stream.i}"

	// encoded stream name
	String name=id+sFuelDelim+streamName
	return name
}

/**
 * Decode a settings string to a search map for the stream
 * @param stream id-canister||name
 * @return [i:id, c: canister, n:name]
 */
@CompileStatic
static Map decodeStreamN(String stream){
	// parse out i, c, n
	//String streamName="${(stream.c ?: sBLK)}||${stream.n}"
	String[] tname=stream.split(sFuelDelim) //id+'-'+streamName
	Integer i=tname[0].toInteger() //"${stream.i}"
	String[] tname1=tname[1].split("\\|\\|") //streamName
	String c=tname1[0]
	String n=tname1[1]

//	if(isEric())myDetail null,"decodeStreamN stream: $stream tname: $tname id: $i tname1: $tname1",iN2

	return [(sI):i, (sC):c, (sN):n]
}

// cache of fuelstreams
@Field static List<Map>fuelFLD

List<Map> gtFuelList(){
	fuelFLD= !fuelFLD ? parent.listFuelStreams(false) : fuelFLD
	return fuelFLD
}

/**
 * Return stream identifier for settings-encoded stream name
 * @param name - settings encoded stream name
 * @return Map [i:, c: , n: ,w:1, t: getFormattedDate(new Date())]
 */
@CompileStatic
Map findStream(String name){
	String s= "findStream $name"
//	if(isEric())myDetail null,s,i1

	Map stream; stream=null
	List<Map>fstreams= gtFuelList()

	if(name){
		Integer i
		String c,n
		i=null; c=null; n=null
		// parse out i, c, n
		Map r=decodeStreamN(name)
		i=iMs(r,sI)
		c=sMs(r,sC)
		n=sMs(r,sN)
		String si=sI
		String sc=sC
		String sn=sN

		stream= fstreams.find{ Map it -> iMs(it,si)==i && sMs(it,sc)==c && sMs(it,sn)==n }
//		if(isEric())myDetail null,s+" found $stream  c: $c i:$i n:$n"
	}

	return stream
}

/**
 * Clear fuel stream settings (for cases only a single data can be used)
 * @param multiple
 */
void clearFvarn(String fvarn, Boolean multiple){
	//String fvarn=multiple ? 'fstreams' : 'fstream_'
	def fl= gtSetting(fvarn)
	if(fl){
		List<String> ifstreams=multiple ? fl : [fl]
		Map stream
		for(String stream_nm in ifstreams){
			stream= findStream(stream_nm)
			// @return Map [i:, c: , n: ,w:1, t: getFormattedDate(new Date())]
			//Map ent=[(sT): 'fuel', id: 'f'+i.toString(), rid: i, sn: stream, displayName: 'Fuel Stream '+i.toString(), n: n, c: c, a: 'stream']
			if(stream){ // stream exists
				String name=encodeStreamN(stream)
				String sid='f'+sMs(stream,sI)
				String attr='stream'
				quantRmInput(sid, attr)
				rmAllowLastInput(sid,attr)
			}
		}
		wremoveSetting(fvarn)
	}
}

/**
 * Clear sensor settings (for cases only a single data can be used)
 * @param multiple
 */
void clearVarn(Boolean multiple){
	String varn=multiple ? 'sensors' : 'sensor_'
	String attrn
	attrn= multiple ? sNL : 'attribute_'
	def sl= gtSetting(varn)
	if(sl){
		List items= multiple ? sl : [sl]
		for(sensor in items){
			String sid='d'+gtSensorId(sensor)
			attrn=multiple ? "attributes_${sid}".toString() : "attribute_"
			def al= gtSetting(attrn)
			if(al){
				List<String> attrs= multiple ? al : [al]
				for(String attr in attrs){
					Map ent=makeSensorDataEntry(sensor,sid,attr)
					quantRmInput(sid,attr)
					if(ent)rmAllowLastInput(ent)
					else rmAllowLastInput(sid,attr)
				}
				wremoveSetting(attrn)
				attrn= sNL
			}
		}
		wremoveSetting(varn)
	}
	if(attrn && gtSetting(attrn)){
		wremoveSetting(attrn)
	}
}

void clearQuants(){
	Integer i
	String sb
	for(i=0; i<10; i++){
		sb= i.toString()
		String sid= 'q'+sb
		String attribute= sid+'attr'

		quantRmInput(sid, attribute)
		rmAllowLastInput(sid,attribute)
		wremoveSetting(sid+'_type')
		wremoveSetting(sid)
	}
}

Boolean hasQuants(){
	Integer i
	String s
	for(i=0; i<10; i++){
		s= i.toString()
		String sid= 'q'+s
		String typ=(String)gtSetting(sid+'_type')
		if(typ in ['Fuel Stream','Sensor']) return true
	}
	return false
}

/**
 * create a fuel stream dataSource entry
 * @param stream
 * @return Map [(sT): 'fuel', id: 'f'+i.toString(), rid: i, sn: stream, displayName: 'Fuel Stream '+i.toString(), n: n, c: c, a: 'stream' q: params]
 */

Map makeFuelDataEntry(String stream, String attr='stream'){
	String s= "makeFuelDataEntry $stream "

	if(!stream) return [:]
	Map r=decodeStreamN(stream)
	Integer i= iMs(r,sI)
	String c= sMs(r,sC)
	String n= sMs(r,sN)
	//[i:app.id, c: 'LTS', n:sid+'_'+attribute,w:1,t: getFormattedDate(new Date())]

//String attribute=c+d+n+d+i.toString()
//String fuelNattr(){

	Map ent
	ent=[(sT): 'fuel', (sID): 'f'+i.toString(), rid: i, sn: stream, (sDISPNM): 'Fuel Stream '+i.toString(), (sN): n, (sC): c, (sA): attr]

	ent += checkLastUpd(ent)

	stToPoll()

	// sensors (devices) are in a setting so they show in use
	// TODO will need to report to parent 'I'm using these fuel streams'

//	if(isEric())myDetail null, s+"ent: $ent", iN2
	return ent
}

/**
 * Set browser polling needs to be done because graph uses fuel stream or synthetic data
 */
void stToPoll(){
	assignSt('hasFuel',true)
	def ii= gtSetting('graph_update_rate')
	Integer i= ii!=null ? "${ii}".toInteger() : -1
	if(i>=0 && i<60000){// remove invalid
		wremoveSetting('graph_update_rate')
	}
}

/**
 * create a sensor dataSource entry
 * @param sensor
 * @param sid
 * @param attr
 * @return Map [(sT): 'sensor', id: sid, rid: sensor.id, displayName: sensor.displayName, a: attr]
 */
Map makeSensorDataEntry(sensor,String sid,String attr){
	String s= "makeSensorDataEntry $sensor $sid $attr "

	if(!sid || !attr) error("no sid or attr sid: $sid attr: $attr",null,iN2)

	Map ent
	ent=[(sT):'sensor', (sID):sid, rid:sensor.id, (sDISPNM):sensor.displayName, (sA):attr]

	Map lu= checkLastUpd(ent)
	if(lu){
		ent += lu
		stToPoll() // javascript code does not handle lastupdate/dynamic updates correctly for the attribute, it uses the sensor
	}

	// sensors (devices) are in a setting so they show in use
	// TODO will need to report to parent 'I'm using these fuel streams'

i//	if(isEric())myDetail null,s+"ent: $ent",iN2
	return ent
}

private String gtSensorId(sensor){
	return sensor.id.toString()
}

Map makeQuantDataEntry(String typ,String sid,String attrn){
	String s= "makeQuantDataEntry $typ $sid $attrn "

	String attribute

	Map ent

	if(typ in ['Fuel Stream']){
		String stream= gtSetting(sid)
		ent=makeFuelDataEntry(stream)
		//ent=[(sT): 'fuel', id: 'f'+i.toString(), rid: i, sn: stream, displayName: 'Fuel Stream '+i.toString(), n: n, c: c, a: attr]
		attribute= attrn
	}else if(typ in ['Sensor']){
		def sensor= gtSetting(sid)
		String msid='d'+gtSensorId(sensor)
		attribute= gtSetting(attrn)
		ent=makeSensorDataEntry(sensor,msid,attribute)
		//ent=[(sT): 'sensor', id: sid, rid: sensor.id, displayName: sensor.displayName, a: attr]
	}else{
		warn "no clear typ $typ",null
//		if(isEric())myDetail null,s+"ent: [:]",iN2
		return [:]
	}

	if(!ent) return [:]

	Map nent
	nent=[(sT): 'quant', (sID): sid, ent: ent, rid: ent.rid, (sDISPNM): sMs(ent,sDISPNM)+' quant', (sA): attribute]

	// if to return data quantized, add to ent
	Map params= quantParams(nent.id,nent.a)
	if(params){
		nent+=[q: params]
		stToPoll()
	}
/*
	Map lu= checkLastUpd(nent)
	if(lu){
		nent += lu
		stToPoll() // javascript code does not handle lastupdate/dynamic updates correctly for the attribute, it uses the sensor
	}
*/
	// sensors (devices) are in a setting so they show in use
	// TODO will need to report to parent 'I'm using these fuel streams'

// if(isEric())myDetail null,s+"ent: $nent",iN2
	return nent
}

/**
 * get state.datasources
 * @return list of data sources
 * <br><br>
 * Map ent=[(sT): 'fuel', id: 'f'+i.toString(), rid: i, sn: stream, displayName: 'Fuel Stream '+i.toString(), n: n, c: c, a: 'stream']
 * <br><br>
 * Map ent=[(sT): 'sensor', id: 'd'+rid, rid: sensor.id, displayName: sensor.displayName, a: attr]
 * <br><br>
 * Map ent=[(sT): 'quant', id: 'q'+[0-10]+'attr', rid: id, displayName: <entered>,  a: 'quant' or actual attr, qp: [params], ent: [sensor or fuel]]
 */
List<Map> gtDataSources(){
	return state.dataSources
}

/**
 * create state.datasources from settings
 * @param multiple - are allowed
 * @return list of data sources and update to state.dataSources
 * <br><br>
 * Map ent=[(sT): 'fuel', id: 'f'+i.toString(), rid: i, sn: stream, displayName: 'Fuel Stream '+i.toString(), n: n, c: c, a: 'stream']
 * <br><br>
 * Map ent=[(sT): 'sensor', id: 'd'+rid, rid: sensor.id, displayName: sensor.displayName, a: attr]
 * <br><br>
 * Map ent=[(sT): 'quant', id: 'q'+[0-10]+'attr', rid: id, displayName: <entered>,  a: 'quant' or actual attr, qp: [params], ent: [sensor or fuel]]
 */
List<Map> createDataSources(Boolean multiple){

	String s= "createDataSources $multiple "
	if(isEric())myDetail null,s,i1

	String fvarn=multiple ? 'fstreams' : 'fstream_'
	String varn=multiple ? 'sensors' : 'sensor_'
	String attrn

	List<Map> dataSources
	dataSources=[]
	state.hasFuel=false

	Boolean hq= hasQuants()
	def sl= gtSetting(varn)
	def fl= gtSetting(fvarn)

	if(fl || sl || hq){

		if(fl){
			if(isEric())myDetail null,s+"processing fuel streams ${fl}",iN2
			List<String> ifstreams=multiple ? fl : [fl]
			for(String stream in ifstreams){
				Map ent=makeFuelDataEntry(stream)
				if(ent)dataSources << ent
			}
		}

		if(sl){
			if(isEric())myDetail null,s+"processing sensors ${sl}",iN2
			List items= multiple ? sl : [sl]
			for(sensor in items){
				String sid='d'+gtSensorId(sensor)
				attrn=multiple ? "attributes_${sid}".toString() : "attribute_"

				def al= gtSetting(attrn)
				if(al){
					List<String> attrs=multiple ? al : [al]
					for(String attr in attrs){
						Map ent=makeSensorDataEntry(sensor,sid,attr)
						if(ent) dataSources << ent
					}
				}else{
					// we don't have complete settings for this sensor...
				}
			}
		}

		if(hq){
			Integer i
			String sb

			for(i=0; i<10; i++){
				sb= i.toString()
				String sid= 'q'+sb
				String attribute= sid+'attr'
				String typ=(String)gtSetting(sid+'_type')
				if(!(typ in ['Fuel Stream','Sensor'])){
					quantRmInput(sid, attribute)
					rmAllowLastInput(sid,attribute)
					wremoveSetting(sid+'_type')
					wremoveSetting(sid)
				}else{
					if(isEric()) myDetail null,s+"processing quants ${sid}",iN2

					Map ent=makeQuantDataEntry(typ,sid,attribute)
					if(ent)dataSources << ent
				}
			}
		}
	}
	if(isEric())myDetail null,s
	state.dataSources=dataSources
	return dataSources
}




def addAllowLastActivity(Map ent){
	String s= "lstUpd_${ent.id}_${ent.a}".toString()
	String name= sMs(ent,sDISPNM)
	String attribute= sMs(ent,sA)
	input( (sTYPE): sBOOL, (sNM): s,(sTIT): "Use last modified time for stream $name attribute $attribute as value?",
			required: false, multiple: false, submitOnChange: false, defaultValue: false)

}

// deal with fuel selection lastupdate - which means last time this stream value was updated
Map checkLastUpd(Map ent){
	String sn= "lstUpd_${ent.id}_${ent.a}".toString()
	if((Boolean)gtSetting(sn)) return [aa:'lastupdate']
	return [:]
}

void rmAllowLastInput(String sid,String attribute){
	String s='lstUpd_'+sid+'_'+attribute
	wremoveSetting(s)
}

void rmAllowLastInput(Map ent){
	String s= "lstUpd_${ent.id}_${ent.a}".toString()
	wremoveSetting(s)
}


/**
 *
 * @param fvarn - creates a setting using this variable name
 * @param ftit
 * @param multiple
 * @param allowLastActivity
 * @return
 */
def gatherFuelSource(String fvarn,String ftit,Boolean multiple,Boolean allowLastActivity){
	if(isEric())myDetail null,"gatherFuelSource $fvarn $multiple",i1
	// fuel streams

	List<Map> final_streams
	List container
	container=[]

	List<Map>fstreams= gtFuelList()

	Integer sz
	sz=fstreams.size()

	if(sz){
		final_streams=[]
		String deflt
		deflt=sBLK

		if(isEric())myDetail null,"gatherFuelSource fuelstreams $sz $fstreams",iN2

		for(Map stream in fstreams){
			// Map [i:, c: , n: ,w:1, t: getFormattedDate(new Date())]

			String name=encodeStreamN(stream)

			List<Map>fdata=parent.readFuelStream(stream)
			sz=fdata.size()
			if(sz){
				//if(!deflt) deflt=name
				final_streams << [(name) : "Fuel Stream $name :: [${fdata[sz-1][sVAL]}]"]
			}
		}

		final_streams=final_streams.unique(false)
		if(final_streams == []){
			container << hubiForm_text("<b>No data found in stream</b><br><small>Please select a different Fuel Stream</small>")
			hubiForm_container(container, 1)
			wremoveSetting(fvarn)
		}else{
//			container << hubiForm_sub_section('Select Fuel Stream')
//			hubiForm_container(container, 1)

			input( (sTYPE): sENUM, (sNM): fvarn,(sTIT): ftit, required: false, multiple: multiple, options: final_streams, defaultValue: deflt, submitOnChange: true )
		}
	}else{
		// no fuel streams
		container << hubiForm_text("<b>No fuel streams found</b><br>")
		hubiForm_container(container, 1)
	}

	def fl= gtSetting(fvarn)
	List<String> ifstreams=multiple ? fl : [fl]
	for(String stream in ifstreams){
		Map ent=makeFuelDataEntry(stream)
		//ent=[(sT): 'fuel', id: 'f'+i.toString(), rid: i, sn: stream, displayName: 'Fuel Stream '+i.toString(), n: n, c: c, a: attr]
		if(ent){
			if(allowLastActivity)
				addAllowLastActivity(ent) // lastupdate
			else rmAllowLastInput(ent)
		}
	}
	if(isEric())myDetail null,"gatherFuelSource $fvarn $multiple"

}

/**
 *
 * @param varn - creates a setting using this variable name
 * @param attrStr - creates a setting using this variable name
 * @return
 */
def gatherSensorSource(String varn, String attrStr, String tit, String cap, String atit, Boolean multiple, Boolean allowLastActivity){

	if(isEric())myDetail null,"gatherSensorSource $varn $multiple",i1

	List<Map> final_attrs
	String attrn
	List container

	input (type: cap, (sNM): varn,(sTIT): tit, multiple: multiple, submitOnChange: true)

	if(settings[varn]){
		List items= multiple ? settings[varn] : [settings[varn]]
		for(sensor in items){
			container=[]
			String sid='d'+gtSensorId(sensor)

			//List attributes_=sensor.getSupportedAttributes()
			List<String> attributes_=sensor.getSupportedAttributes().collect{ it.getName() }.unique().sort()
			final_attrs=[]

			String deflt; deflt=sBLK
			for(String attribute in attributes_){
				//String name=attribute.getName()
				def v= sensor.currentState(attribute,true)
				if(v!=null){
					if(!deflt) deflt=attribute
					final_attrs << [(attribute) : "$attribute ::: [${v.getValue()}]"]
				}
			}
			//if(allowLastActivity) final_attrs << ["lastupdate": "last activity ::: [${sensor.getLastActivity()}]"]
			final_attrs=final_attrs.unique(false)

			attrn= attrStr + (multiple ? sid:sBLK)

			if(final_attrs == []){
				container << hubiForm_text("<b>No supported Numerical Attributes</b><br><small>Please select a different Sensor</small>")
				hubiForm_container(container, 1)
				wremoveSetting(attrn)
			}else{
				container << hubiForm_sub_section("${sensor.displayName}")
				hubiForm_container(container, 1)

				input( (sTYPE): sENUM, (sNM): attrn,(sTIT): atit, required: false, multiple: multiple, options: final_attrs, defaultValue: deflt, submitOnChange: true )

			}

			def al= gtSetting(attrn)
			if(al){
				List<String> attrs=multiple ? al : [al]
				for(String attr in attrs){
					Map ent=makeSensorDataEntry(sensor,sid,attr)
					if(ent){
						if(allowLastActivity)
							addAllowLastActivity(ent) // lastupdate
						else rmAllowLastInput(ent)
					}else
						rmAllowLastInput(sid,attr)
				}
			}
		}
	}
	if(isEric())myDetail null,"gatherSensorSource $varn $multiple"
}


def gatherQuantSource(Boolean multiple,Boolean allowLastActivity){
	if(isEric())myDetail null,"gatherQuantSource $multiple",i1

	List container
	container=[]

	Integer i
	String s,saveS
	saveS=sNL
	Boolean fndf
	fndf=false

	for(i=0; i<10; i++){
		s= i.toString()
		String sid= 'q'+s
		//String attribute= sid+'attr'
		String typ=(String)gtSetting(sid+'_type')
		if(typ in ['Fuel Stream','Sensor']){
			displayQuant(sid,allowLastActivity,false)
		}else if(saveS==sNL){ saveS=s; fndf=true}
	}

	if(fndf){
		displayQuant('q'+saveS,allowLastActivity,true)
	}else{
		container << hubiForm_text("<b>Did not find free slot quant list</b><br><small>More than 10 quants for chart</small>")
		hubiForm_container(container, 1)
	}
	if(isEric())myDetail null,"gatherQuantSource $multiple"
}

def displayQuant(String sid, Boolean allowLastActivity, Boolean create=false){
	List container
	container=[]

	String attribute= sid+'attr'

	Boolean attrOk
	attrOk=false
	List<String> opts
	opts= ['None','Fuel Stream','Sensor']

	if(!create){
		String typ=(String)gtSetting(sid+'_type')
		container << hubiForm_sub_section("Quantized source $sid")

		opts= ['None',typ] // allow to turn off
		container << hubiForm_enum ((sTIT):	"Source type",
				(sNM):	sid+'_type',
				list:	opts,
				default: typ,
				submit_on_change: true)

	}else{
		container << hubiForm_sub_section("Create new quantized source $sid")

		container << hubiForm_enum ((sTIT):	"Source type",
				(sNM):	sid+'_type',
				list:	opts,
				default: 'None',
				submit_on_change: true)

	}
	hubiForm_container(container, 1)

	String typ=(String)gtSetting(sid+'_type')
	if(typ=='Fuel Stream'){
		gatherFuelSource(sid,'Source Fuel Stream',false,allowLastActivity)
		attrOk=true
	}else if(typ=='Sensor'){
		gatherSensorSource(sid,attribute,'Source Sensor','capability.*','Attribute',false,allowLastActivity)
		if(gtSetting(attribute)) attrOk=true

	}else{
		// remove settings junk
		wremoveSetting(sid)
		quantRmInput(sid, attribute)
		rmAllowLastInput(sid,attribute)
	}
	if(gtSetting(sid) && attrOk){
		//if(allowLastActivity) addAllowLastActivity(sid, attribute) // lastupdate
		quantInput(sid,attribute)
	}

}

def quantInput(String sid, String attribute){
	if(isEric())myDetail null,"quantInput $sid $attribute",iN2
	String s="${sid}_${attribute}".toString()

	List<Map<String,String>> quantizationEnum=[
			["0": "None"], ["5" : "5 Minutes"], ["10" : "10 Minutes"], ["20" : "20 Minutes"], ["30" : "30 Minutes"],
			["60" : "1 Hour"], ["120" : "2 Hours"], ["180" : "3 Hours"], ["240" : "4 Hours"], ["360" : "6 Hours"],
			["480" : "8 Hours"], ["1440" : "24 Hours"], ["10080": "7 Days"]]

	List<Map<String,String>> quantizationFunctionEnum=[
			[(sNONE): "No Quantization"], ["sum": "Sum Values"], ["average" : "Average Values"], ["count" : "Count Events"],
			["min" : "Minimum Value"], ["max" : "Maximum Value"]]

	//paragraph('Return Quantize data when read? (None means no quantization)')
	Boolean remove
	remove=false

	String sq=s+'_quantization'
	if(isEric())myDetail null,"quantInput $sid $attribute ${sq}  ${gtSetting(sq)}",iN2
	input( (sTYPE): sENUM, (sNM): sq,(sTIT): "Data Quantization Timeframe (None means disabled)",
			required: false, multiple: false, options: quantizationEnum, submitOnChange: true, defaultValue: s0)

	String sqv= gtSetting(sq)
	if(sqv && !(sqv in ['0']) ){
		String sf= s+'_quantization_function'
		input( (sTYPE): sENUM, (sNM): sf,(sTIT): "Quantization Function",
				required: false, multiple: false, options: quantizationFunctionEnum, submitOnChange: true, defaultValue: "average")

		String sfv= gtSetting(sf)
		if(sfv && sfv != sNONE){
			input( (sTYPE): sBOOL, (sNM): s+"_boundary",(sTIT): "Quantize Data to Hour/Day Boundary (true changes reading time)?",
					required: false, multiple: false, submitOnChange: true, defaultValue: false)

			input( (sTYPE): sENUM, (sNM): s+"_quantization_decimals",(sTIT): "Quantization Decimals to Maintain",
					required: false, multiple: false, options: [[0: "Zero"], [1: "One"], [2: "Two"], [3: "Three"], [4: "Four"]],
					submitOnChange: true, defaultValue: s1)

		}else{
			remove=true
		}
	}else{
		remove=true
	}
	if(remove){
		if(isEric())myDetail null,"quantInput removing",iN2
		quantRmInput(sid, attribute)
	}
}

void quantRmInput(String sid, String attribute){
	String s=sid+'_'+attribute
	wremoveSetting(s+'_quantization_function')
	wremoveSetting(s+'_boundary')
	wremoveSetting(s+'_quantization_decimals')
	wremoveSetting(s+'_quantization')

}




/** gather data source inputs for a graph
 *
 * @param multiple - allow multiple sources
 * @param ordered - do ordering
 * @param cap - capability for selection sensor devices
 * @return screens, and updates settings
 */
def gatherDataSources(Boolean multiple=true, Boolean ordered=false, Boolean allowLastActivity=false, String cap="capability.*"){

	if(isEric())myDetail null,"gatherDataSources $multiple",i1

	String fvarn=multiple ? 'fstreams' : 'fstream_'
	String ftit=multiple ? 'Choose fuel streams' : 'Fuel Stream'
	String varn=multiple ? 'sensors' : 'sensor_'
	String tit=multiple ? 'Choose sensors' : 'Sensor'
	String atit=multiple ? 'Attributes to graph' : 'Attribute for Gauge'

	List<Map> a=createDataSources(multiple)

	hubiForm_section("Data Source Selection", 1, sBLK, sBLK){

		Boolean hq= hasQuants()
		def sl,fl
		sl= gtSetting(varn)

		// fuel streams
		if(!multiple && (sl || hq) ) clearFvarn(fvarn,multiple)

		if(multiple || (!multiple && !sl && !hq)){
			gatherFuelSource(fvarn,ftit,multiple,allowLastActivity)
		}

		fl= gtSetting(fvarn)

		// sensors
		if(!multiple && (fl || hq) ) clearVarn(multiple)

		if(multiple || (!multiple && !fl && !hq)){
			String attrn=multiple ? "attributes_": "attribute_"
			gatherSensorSource(varn, attrn, tit, cap, atit, multiple, allowLastActivity)
		}

		sl= gtSetting(varn)
		fl= gtSetting(fvarn)

		// calculated, virtual, quant
		if(!multiple && (fl || sl) ) clearQuants()

		if(multiple || (!multiple && !fl && !sl)){
			gatherQuantSource(multiple,allowLastActivity)
		}
	}

/*	wremoveSetting('f1_1 - test||temp_quantization')
	wremoveSetting('q0_q0_attr_quantization_function')
 */
	state.remove('lastOrder')

	List<Map> dataSources= createDataSources(multiple)

	Integer sz=dataSources.size()

	if(ordered && multiple && sz>1){
		if(isEric())debug "check order",null
		List<String> all=(1..sz).collect{ Integer it -> sBLK + it.toString() }
		hubiTools_validate_order(all)
	}
	if(isEric())myDetail null,"gatherDataSources $multiple"
}




/* shared pages */

//def mainGauge(){
//def mainBar(){
//def mainTimeline(){
//def mainHeatmap(){
//def mainLinegraph(){
//def mainRangebar(){
//def mainTimegraph(){
def mainShare1(String instruct, String okSet,Boolean multiple=true){
	if(isEric())myDetail null,"mainShare1: $okSet $multiple",iN2
	List a=createDataSources(multiple)
	if(!state.dataSources) wremoveSetting(okSet)
	if(instruct) state.devInstruct=instruct

	dynamicPage(name: "mainPage"){

		checkDup()
		if(!state.endpoint){
			hubiForm_section("Please set up OAuth API", 1, "report", sBLK){
				href( (sNM): "enableAPIPageLink",(sTIT): "Enable API", description: sBLK, page: "enableAPIPage")
			}
		}else{
			hubiForm_section(tDesc()+" Graph Options", 1, "tune", sBLK){
				List container
				container=[]
				container << hubiForm_page_button("Select Data Source(s)", "deviceSelectionPage", "100%", "vibration")
				container << hubiForm_page_button("Configure Graph", "graphSetupPage", "100%", "poll")

				hubiForm_container(container, 1)
			}

			if(settings[okSet]!=null){
				local_graph_url()
				preview_tile()
			}

			put_settings()
		}
	}
}



//def deviceGauge(){
//def deviceBar(){
//def deviceTimeline(){
//def deviceTimegraph(){
//def deviceHeatmap(){
//def deviceLinegraph(){
//def deviceRangebar(){
def deviceShare1(Boolean multiple=true, Boolean ordered=false,Boolean allowLastActivity=false){

	if(isEric())myDetail null,"deviceShare1: $ordered",iN2
	dynamicPage(name: "deviceSelectionPage", nextPage:"attributeConfigurationPage"){
		if(state.devInstruct){
			List container
			hubiForm_section("Directions", 1, "directions", sBLK){
				container=[]
				container << hubiForm_text((String)state.devInstruct)
				hubiForm_container(container, 1)
			}
		}
		gatherDataSources(multiple, ordered, allowLastActivity)
	}
}



//def attributeTimegraph(){
//def attributeHeatmap(){
//def attributeLinegraph(){
def attributeShare1(Boolean ordered=false, String var_color="background"){
	if(isEric())myDetail null,"attributeShare1: $ordered $var_color",iN2

	List<Map> dataSources= createDataSources(true)

	dynamicPage(name: "attributeConfigurationPage", nextPage:"graphSetupPage"){

		if(ordered){
			hubiForm_section("Graph Order", 1, "directions", sBLK, sBLK){
				hubiForm_list_reorder("graph_order", var_color)
			}
		}
		List container

		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String rid=ent.rid.toString()
			String attribute=sMs(ent,sA)
			String dn= sMs(ent,sDISPNM)
			String typ=sMs(ent,sT).capitalize()
			String hint= typ=='Fuel' ? " (Canister ${ent.c} Name ${ent.n})" : sBLK
			String sa="${sid}_${attribute}".toString()

			container=[]

			hubiForm_section("${sLblTyp(ent[sT])}${dn} - ${attribute}${hint}", 1, "directions", sid+attribute){
				if(typ=='Sensor'){
					String tvar="var_"+sa+"_lts"
					if((Boolean)parent.ltsAvailable(rid, attribute)){
						container << hubiForm_sub_section("Long Term Storage in use")

					}else{
						app.updateSetting(tvar, false)
						settings[tvar]= false
					}
				}

				container << hubiForm_sub_section("Override ${typ} Name on Graph")

				container << hubiForm_text_input("<small></i>Use %deviceName% for DEVICE and %attributeName% for ATTRIBUTE</i></small>",
						"graph_name_override_"+sa,
						"%deviceName%: %attributeName%", false)
				hubiForm_container(container, 1)
			}
		}
	}
}

def local_graph_url(){
	List container
	container=[]
	hubiForm_section("Local Graph URL", 1, "link", sBLK){
		String s= "${state.localEndpointURL}graph/?access_token=${state.endpointSecret}"
		container << hubiForm_text(s, s)

		hubiForm_container(container, 1)
	}
}

def preview_tile(){
	List container
	String typ=sMs(settings,sGRAPHT)
	hubiForm_section("Preview of graph (sTYPE): ${typ}", 10, "show_chart", sBLK){
		container=[]
		container << hubiForm_graph_preview()

		hubiForm_container(container, 1)
	}

	install_tile()
}

def install_tile(){
	List container
	String s=app_name ?: tDesc()
	hubiForm_section(s+" Tile Installation", 2, "apps", sBLK){
		container=[]

		container << hubiForm_switch([(sTIT): "Install ${s} Tile Device?", (sNM): "install_device", default: false, submit_on_change: true])
		if(install_device==true){
			container << hubiForm_text_input("Name for ${s} Tile Device", "device_name", "${s} Tile", true)
		}
		hubiForm_container(container, 1)
	}
}

def put_settings(Boolean needOauth=true){
	if(!needOauth || state.endpoint){
		String typ=tDesc()
		List container
		container=[]
		hubiForm_section("webCoRE ${typ} Application Settings", 1, "settings", sBLK){
			container << hubiForm_sub_section("Application Name")
			if( sMs(settings,sGRAPHT)!=sLONGTS){
				container << hubiForm_text_input("Rename the Application?", "app_name", "webCoRE ${typ}", true)
			}else app.updateSetting('app_name', "webCoRE ${typ}") // cannot rename LTS
			container << hubiForm_sub_section("Debugging")

			container << hubiForm_enum((sTIT):	"Logging Level",
					(sNM):	sLOGNG,
					list:	[s0,s1,s2,"3"],
					default: state[sLOGNG] ? state[sLOGNG].toString():s0 )

			if(needOauth && state.endpoint){
				container << hubiForm_sub_section("Disable Oauth Authorization")
				container << hubiForm_page_button("Disable API", "disableAPIPage", "100%", "cancel")
			}

			hubiForm_container(container, 1)
		}
	}
}


/**
 * get data source entry for sid/attribute pair
 */
Map findDataSourceEntry(String sid, String attribute){
	Map ent
	ent= null
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		ent= dataSources.find{ Map it -> it.id==sid && sMs(it,sA)==attribute }
	}
	if(isEric())myDetail null,"findDataSourceEntry: $sid $attribute $ent",iN2
	return ent
}

/**
 * get Last data item from a data source entry as internal map
 * @return [date: (Date)date, (sVAL): v, t: (Long)t]
 */
Map gtLastData(Map ent, Boolean multiple=true){
	Map lst
	lst=null
	List<Map> fdata= gtDataSourceData(ent,multiple)
	Integer sz
	sz= fdata.size()
	if(sz){
		lst= fdata[sz-1]
	}
	return lst
}

/**
 * get last value as float from a data Source entry as a special map
 * @return - [current: (float)val, date: (Date)d]
 */
Map gtFloatMap(Map ent, Boolean multiple=true){
	Map res
	res=[:]
	Map lst= gtLastData(ent, multiple)
	// [date: date, (sVAL): v, t: t]
	if(lst){
		Float val= "${lst[sVAL]}".toFloat()
		Date dt= (Date)lst[sDT]
		res= [current: val, (sDT): dt]
	}
	if(isEric())myDetail null,"gtFloatMap $ent $multiple $res ",iN2
	return res
}

/**
 * return a map of last item in a data source entry
 * @return - [(sVAL): (String)x, date: (Date)d]
 */
Map gtLatestMap(Map ent, Boolean multiple=true){
	Map res
	res=[:]
	Map lst= gtLastData(ent, multiple)
	if(lst){
		// [date: date, (sVAL): v, t: t]

		String val= "${lst[sVAL]}"
		Date dt= (Date)lst[sDT]
		res= [(sVAL): val, (sDT): dt]
	}
	if(isEric())myDetail null,"gtLatestMap $ent $multiple $res ",iN2
	return res
}

/**
 * get latest double value of data source entry
 * @return doubleVal
 */
Double getLatestVal(Map ent, Boolean multiple=true){
	String val
	val='0.0'

	Map lst= gtLastData(ent, multiple)
	if(lst){
		val= "${lst[sVAL]}"
	}

	if(isEric())myDetail null,"getLatestVal $ent $multiple $val ",iN2
	return extractNumber(val)
}

/**
 * get latest double value of data source entry with override from settings
 * @return doubleVal
 */
private Double getValue(String id, String attr, val){
	Double ret
	String s
	if(settings["attribute_${id}_${attr}_${val}"]!=null){
		s= settings["attribute_${id}_${attr}_${val}"].toString()
	}else{
		s= "${val}".toString()
	}
	ret= extractNumber(s)
	return ret
}

static Double extractNumber(String input){
	List<Double>val=input.findAll( /-?\d+\.\d*|-?\d*\.\d+|-?\d+/ )*.toDouble()
	val[0]
}

/**
 * get data source data from entry later than time
 * Shared - used by graphs to returns data later than time
 * @return internal format [[date: (Date)date, (sVAL): v, t: (Long)t]...]
 */
List<Map> CgetData(Map ent, Date time, Boolean multiple=true){

	List<Map> return_data

	Long end=time.getTime()

	return_data= gtDataSourceData(ent,multiple)

	List<Map> data2
	data2=return_data.findAll{ Map it -> (Long)it.t > end }

	if(!data2) data2= return_data ? [return_data[-1]] : data2

	if(isEric())myDetail null,"CgetData: $ent $time ${data2.size()}",iN2
	return data2
}

/**
 * get data source data from entry later than time
 * Shared - get all data for a datasource entry; if quant enabled, data will be quanted
 * @param sensorV - settings variable name for sensor type  (override)
 * @return internal format [[date: (Date)date, (sVAL): v, t: (Long)t]...]
 */
List<Map> gtDataSourceData(Map ent, Boolean multiple=true, String sensorV=sNL){
	if(isEric())myDetail null,"gtDataSourceData $ent $multiple",i1

	String attribute=sMs(ent,sA)
	String typ= sMs(ent,sT).capitalize()

	List<Map>res
	res=[]

	if(typ=='Fuel'){
		Map stream= findStream(ent.sn)
		if(stream)
			res=parent.readFuelStream(stream)
		else warn 'gtDataSourceData: stream not found',null
	}

	if(typ=='Sensor'){
		String varn= sensorV ?: (multiple ? 'sensors' : 'sensor_') // have to get devices from settings
		def a=gtSetting(varn)
		List devs= multiple ? a : [a]

		String rid=ent.rid.toString()
		if(isEric())myDetail null,"varn: $varn devs ${devs} a: ${a}  rid: ${myObj(ent.rid)}",iN2

		if(devs.size()){
			def sensor=devs.find{
//				myDetail null,"${it.id} ${myObj(it.id)} ${myObj(rid)}",iN2
				it.id == rid }
//			myDetail null,"sz is ${devs.size()} $attribute $sensor",iN2
			if(sensor && attribute){
//				myDetail null,"have sensor and attribute",iN2
				res= getAllData(sensor,attribute,1461,true,false)
			}
		}else warn 'gtDataSourceData: no devices found',null
	}

	if(typ=='Quant'){
		res=gtDataSourceData(ent.ent,false, (String)ent.id)

		// if to return data quantized
		Map params= quantParams(ent.id,attribute)
		if(res && params)
			res= quantizeData(res, params.qm , params.qf, params.qd, params.qb, false)
	}

	if(isEric())myDetail null,"gtDataSourceData ${ent} ${res.size()}"
	return res
}





static String scriptIncludes(){
	String html= """
		<script src="https://code.jquery.com/jquery-3.5.0.min.js" integrity="sha256-xNzN2a4ltkB44Mc/Jz3pT4iU1cmeR0FkXs4pru/JxaQ=" crossorigin="anonymous"></script>
		<script src="https://cdnjs.cloudflare.com/ajax/libs/moment.js/2.29.3/moment.min.js" integrity="sha256-7jipyThfvhNeS3Iv+glwpMOCkQ68sGHozhbb5mI4OCg=" crossorigin="anonymous"></script>
		<script src="https://cdnjs.cloudflare.com/ajax/libs/he/1.2.0/he.min.js" integrity="sha256-awnFgdmMV/qmPoT6Fya+g8h/E4m0Z+UFwEHZck/HRcc=" crossorigin="anonymous"></script>
		<script type="text/javascript" src="https://www.gstatic.com/charts/loader.js"></script>
"""
	return html
}

static String scriptIncludes1(){
	String html="""
${scriptIncludes()}
		<script src="/local/${isSystemType() ? 'webcore/' : ''}a930f16d-d5f4-4f37-b874-6b0dcfd47ace-HubiGraph.js"></script>
"""
	return html
}






/*
 * TODO: Gauge methods
 */

def mainGauge(){
	mainShare1("Choose Numeric Attributes only",'gauge_title',false)
}

def deviceGauge(){
	deviceShare1(false)
}

def attributeGauge(){
	List<Map> dataSources= createDataSources(false)

	dynamicPage(name: "attributeConfigurationPage", nextPage:"graphSetupPage"){
		List container
		def val
		String dn
		dn='unknown'
		String typ
		typ=dn

		if(dataSources){
			for(Map ent in dataSources){

				List<Map>fdata=gtDataSourceData(ent,false)
				Integer sz
				sz=fdata.size()
				if(sz){ val="${fdata[sz-1][sVAL]}" }
				typ=((String)ent[sT]).capitalize()
				dn= sMs(ent,sDISPNM)

				String hint= typ=='Fuel' ? " (Canister ${ent.c} Name ${ent.n})" : sBLK
				String sid=sMs(ent,sID)
				String rid=ent.rid.toString()
				String attribute=sMs(ent,sA)
				String sa="${sid}_${attribute}".toString()

				hubiForm_section("${sLblTyp(ent[sT])}${dn} - ${attribute}${hint}", 1, "directions", sid+attribute){

					if(typ=='Sensor'){
						String tvar="var_"+sa+"_lts"
						if((Boolean)parent.ltsAvailable(rid, attribute)){
							hubiForm_section("${sLblTyp(ent[sT])}${dn} - ${attribute}", 1, "directions", sid+attribute){
								container=[]
								container << hubiForm_sub_section("Long Term Storage in use")
								hubiForm_container(container, 1)
							}

						}else{
							app.updateSetting(tvar, false)
							settings[tvar]= false
						}
					}
				}
			}

			if(val != null){
				hubiForm_section("Min Max Value", 1, sBLK, sBLK){
					container=[]
					container<< hubiForm_text("<b>Current ${typ} Value=</b>$val")
					container << hubiForm_text_input ("Minimum Value for Gauge", "minValue_", s0, false)
					container << hubiForm_text_input ("Maximum Value for Gauge", "maxValue_", "100", false)
					hubiForm_container(container, 1)
				}
			}else{
				hubiForm_section("No data", 1, sBLK, sBLK){
					container=[]
					container<< hubiForm_text("<b>No recent valid ${typ} data for ${dn}</b><br><small>Please select a different data Source</small>")
					hubiForm_container(container, 1)
				}
			}
		}
	}
}

def graphGauge(){

	Integer num_

	dynamicPage(name: "graphSetupPage"){
		List container
		hubiForm_section("General Options", 1, sBLK, sBLK){
			container=[]
			if((Boolean)state.hasFuel)
				inputGraphUpdateRate()
			else
				app.updateSetting("graph_update_rate", '0')

			container << hubiForm_text_input ("Gauge Title", "gauge_title", "Gauge Title", false)
			container << hubiForm_text_input ("Gauge Units", "gauge_units", "Units", false)
			container << hubiForm_text_input ("Gauge Number Formatting<br><small>Example</small>", "gauge_number_format", "##.#", false)

			container << hubiForm_slider ((sTIT): "Select Number of Highlight Areas on Gauge", (sNM): "num_highlights", default: 3, min: 0, max: 3, units: " highlights", submit_on_change: true)

			hubiForm_container(container, 1)
		}

		if(num_highlights == null){
			settings["num_highlights"]=3
			app.updateSetting("num_highlights", 3)
			num_=3
		}else{
			num_=num_highlights.toInteger()
		}

		if(num_ > 0){
			hubiForm_section("HighLight Regions", 1, sBLK, sBLK){
				container=[]
				String color_
				color_=sNL
				Integer i
				for(i=0; i<num_; i+=1){
					switch (i){
						case 0 : color_="#00FF00"; break
						case 1 : color_="#a9a67e"; break
						case 2 : color_="#FF0000"; break
					}
					container << hubiForm_color	("Highlight $i", "highlight${i}", color_, false)
					container << hubiForm_text_input ("Select Highlight Start Region Value ${i}", "highlight${i}_start", sBLK, false)
				}
				container << hubiForm_text_input ("Select Highlight End Region Value ${i-1}", "highlight_end", sBLK, false)
				hubiForm_container(container, 1)
			}
		}

		hubiForm_section("Major and Minor Tics", 1, sBLK, sBLK){
			container=[]
			container << hubiForm_slider ((sTIT): "Number Minor Tics", (sNM): "gauge_minor_tics", default: 3, min: 0, max: 10, units: " tics")

			container << hubiForm_switch ([(sTIT): "Use Custom Tics/Labels", (sNM): "default_major_ticks", default: false, submit_on_change: true])
			if(default_major_ticks == true){
				if(gauge_major_tics == null){
					settings["gauge_major_tics"]=3
					app.updateSetting("gauge_major_tics", 3)
				}
				container << hubiForm_slider ((sTIT): "Number Major Tics", (sNM): "gauge_major_tics", default: 3, min: 0, max: 20, units: " tics")
				Integer tic
				for(tic=0; tic<gauge_major_tics.toInteger(); tic++){
					container << hubiForm_text_input ("Input the Label for Tick ${tic+1}", "tic_title${tic}", "Label", false)
				}
			}
			hubiForm_container(container, 1)
		}
	}
}


String getData_gauge(){

	String val
	val='0.0'

	List<Map> dataSources=gtDataSources()
	if(dataSources){
		Map ent= dataSources[0]
		val= "${getLatestVal(ent,false)}"
	}

//	return extractNumber(val)
	return JsonOutput.toJson( [(sVAL): extractNumber(val)] )
}

Map getOptions_gauge(){

	List tic_labels
	tic_labels=[]
	if(default_major_ticks == true){
		Integer tic
		for(tic=0; tic<gauge_major_tics.toInteger(); tic++){
			tic_labels += (String)settings["tic_title${tic}"]
		}
	}

	String redColor, redFrom, redTo, yellowColor, yellowFrom, yellowTo, greenColor, greenFrom, greenTo
	redColor=""
	redFrom=""
	redTo=""
	yellowColor=""
	yellowFrom=""
	yellowTo=""
	greenColor=""
	greenFrom=""
	greenTo=""

	switch (num_highlights.toInteger()){

		case 3:
			redColor=highlight2_color_transparent ? "transparent" : highlight2_color
			redFrom=highlight2_start
			redTo=highlight_end

			yellowColor=highlight1_color_transparent ? "transparent" : highlight1_color
			yellowFrom=highlight1_start
			yellowTo=highlight2_start

			greenColor=highlight0_color_transparent ? "transparent" : highlight0_color
			greenFrom=highlight0_start
			greenTo	= highlight1_start

			break

		case 2:

			yellowColor=highlight1_color_transparent ? "transparent" : highlight1_color
			yellowFrom=highlight1_start
			yellowTo=highlight_end

			greenColor=highlight0_color_transparent ? "transparent" : highlight0_color
			greenFrom=highlight0_start
			greenTo	= highlight1_start

			break

		case 1:

			greenColor=highlight0_color_transparent ? "transparent" : highlight0_color
			greenFrom=highlight0_start
			greenTo	= highlight_end

			break
	}
	Map options=[
		"graphUpdateRate": Integer.parseInt(graph_update_rate),
		"graphOptions": [
			"width": graph_static_size ? graph_h_size : "100%",
			"height": graph_static_size ? graph_v_size: "100%",
			"min": minValue_,
			"max": maxValue_,
			"greenFrom": greenFrom,
			"greenTo": greenTo,
			"greenColor": greenColor,
			"yellowFrom": yellowFrom,
			"yellowTo": yellowTo,
			"yellowColor": yellowColor,
			"redFrom": redFrom,
			"redTo": redTo,
			"redColor": redColor,
			"backgroundColor": graph_background_color_transparency ? "transparent": graph_background_color,
			"majorTicks" : default_major_ticks == true ? tic_labels : sBLK,
			"minorTicks" : gauge_minor_tics
		]
	]

	return options
}

String getGraph_gauge(){
	String fullSizeStyle="margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden"

	String html="""
<html style="${fullSizeStyle}">
	<head>
${scriptIncludes()}
		<script type="text/javascript">
google.charts.load('current',{'packages':['gauge']});

let options=[];
let subscriptions={};
let graphData={};

let websocket;

class Loader{
	constructor(){
		this.elem=jQuery(jQuery(document.body).prepend(`
			<div class="loaderContainer">
				<div class="dotsContainer">
					<div class="dot"></div>
					<div class="dot"></div>
					<div class="dot"></div>
				</div>
				<div class="text"></div>
			</div>
		`).children()[0]);
	}

	setText(text){
		this.elem.find('.text').text(text);
	}

	remove(){
		this.elem.remove();
	}
}

function getOptions(){
	return jQuery.get("${state.localEndpointURL}getOptions/?access_token=${state.endpointSecret}", (data) =>{
		options=data;
		console.log("Got Options");
		console.log(options);
	});
}

function getSubscriptions(){
	return jQuery.get("${state.localEndpointURL}getSubscriptions/?access_token=${state.endpointSecret}", (data) =>{
		console.log("Got Subscriptions");
		console.log(data);
		subscriptions=data;

	});
}

function getGraphData(){
	return jQuery.get("${state.localEndpointURL}getData/?access_token=${state.endpointSecret}", (data) =>{
		console.log("Got Graph Data");
		console.log(data);
		graphData=data;
	});
}

function parseEvent(event){
	let deviceId=event.deviceId;

	//only accept relevent events
	if(subscriptions.id == deviceId && subscriptions.attribute.includes(event.name)){
		let value=event.value;

		graphData.value=parseFloat(value.match(/[0-9.]+/g)[0]);

		update();
	}
}

function update(callback){
	drawChart(callback);
}

async function aupdate(){
	let old=graphData.value;
	await getGraphData();
	if(old != graphData.value) drawChart();
}

async function onLoad(){
	//let loader=new Loader();

	//first load
	//loader.setText('Getting options (1/4)');
	await getOptions();
	//loader.setText('Getting device data (2/4)');
	await getSubscriptions();
	//loader.setText('Getting events (3/4)');
	await getGraphData();
	//loader.setText('Drawing chart (4/4)');

	update(() =>{
		//destroy loader when we are done with it
		//loader.remove();
	});

	if(subscriptions.id=='poll'){
		if(options.graphUpdateRate > 0){
			setInterval(() =>{
				aupdate();
			}, options.graphUpdateRate);
		}
	} else{

		//start our update cycle
		//start websocket
		websocket=new WebSocket("ws://" + location.hostname + "/eventsocket");
		websocket.onopen=() =>{
			console.log("WebSocket Opened!");
		}
		websocket.onmessage=(event) =>{
			parseEvent(JSON.parse(event.data));
		}
	}

	//attach resize listener
	window.addEventListener("resize", () =>{
		drawChart();
	});
}

function onBeforeUnload(){
	if(websocket) websocket.close();
}

function drawChart(){
	let dataTable=new google.visualization.DataTable();
	dataTable.addColumn('string', 'Label');
	dataTable.addColumn('number', 'Value');
	dataTable.addRow(['${gauge_title}', graphData.value]);

	var formatter=new google.visualization.NumberFormat(
		{suffix: "${gauge_units}", pattern: "${gauge_number_format}"}
	);
	formatter.format(dataTable, 1);

	let chart=new google.visualization.Gauge(document.getElementById("timeline"));
	chart.draw(dataTable, options.graphOptions);
}

google.charts.setOnLoadCallback(onLoad);
window.onBeforeUnload=onBeforeUnload;
		</script>
	</head>
	<body style="${fullSizeStyle}">
		<div id="timeline" style="${fullSizeStyle}" align="center"></div>
	</body>
</html>
"""

	return html
}


//oauth endpoints

Map getSubscriptions_gauge(){

	Map subscriptions
	subscriptions=[:]

	List<Map> dataSources=gtDataSources()
	if(dataSources){

		Map ent= dataSources[0]
		String typ=((String)ent[sT]).capitalize()

		if(typ=='Sensor'){
			subscriptions=[
				(sID): ent.rid,
				(sATTR): sMs(ent,sA)
			]
		}else{
			subscriptions=[
				(sID): 'poll',
				(sATTR): sNONE
			]
		}
	}
	return subscriptions
}









// Shared input method
def inputGraphUpdateRate(){
	String defl
	defl=s0
	List opt
	opt=rateEnum
	if((Boolean)state.hasFuel){
		stToPoll()
		defl="600000"
		opt=rateEnumF
	}

	input( (sTYPE): sENUM, (sNM): "graph_update_rate",(sTIT): "<b>Select graph update rate</b>", multiple: false, required: false, options: opt, defaultValue: defl)
}


/** refresh rate for graphs with fuel streams */
@Field static List<Map> rateEnumF=[
		["-1":"Never"], // ["0":"Real Time"],
//		["10":"10 Milliseconds"], ["1000":"1 Second"], ["5000":"5 Seconds"],
		["60000":"1 Minute"], ["300000":"5 Minutes"], ["600000":"10 Minutes"], ["1800000":"Half Hour"], ["3600000":"1 Hour"]
]

/** refresh rate for graphs with only sensors */
@Field static List<Map> rateEnum=[
		["-1":"Never"], ["0":"Real Time"],
		["10":"10 Milliseconds"], ["1000":"1 Second"], ["5000":"5 Seconds"],
		["60000":"1 Minute"], ["300000":"5 Minutes"], ["600000":"10 Minutes"], ["1800000":"Half Hour"], ["3600000":"1 Hour"]
]


// shared by bar, timeline, heatmap, rangebar
Map gtSensorFmt(Boolean curStates=false,Boolean multiple=true){
	if(isEric())myDetail null,"gtSensorFmt curStates: $curStates, multiple: $multiple",i1
	Map sensors_fmt
	sensors_fmt=[:]

	//TODO
	List<Map> dataSources=gtDataSources()
	Map<String,List<Map>> res = [:]

	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)
			String s=sid

			Map tres
			res[s]= res[s] ?: []

			if(curStates){
				tres= gtLatestMap(ent,multiple)
				res[s] << [(sNM): attribute, (sVAL): tres]
			}
		}
		for(Map ent in dataSources){
			String sid=sMs(ent,sID)
			String dn= sMs(ent,sDISPNM)
			String s=sid
			sensors_fmt[sid]=[ (sID): sid, (sDISPNM): dn] + (curStates ? ["currentStates": res[s] ] : [:])
		}
	}

	if(isEric())myDetail null,"gtSensorFmt  $curStates $sensors_fmt"
	return sensors_fmt
}

static String sLblTyp(String typ){
	if(typ=='fuel') return sBLK
	else return 'Sensor '
}








/*
 * TODO: Bar methods
 */

def mainBar(){
	mainShare1('Choose Numeric Attributes only','graph_update_rate')
}

def deviceBar(){
	deviceShare1()
}

def attributeBar(){
	List<Map> dataSources= createDataSources(true)

	dynamicPage(name: "attributeConfigurationPage", nextPage:"graphSetupPage"){
		List container

		hubiForm_section("Graph Order", 1, "directions", sBLK){
			hubiForm_list_reorder("graph_order", "background")
		}

		List<Map> decimalsEnum=[ [0:"None (123)"], [1: "One (123.1)"], [2: "Two (123.12)"], [3: "Three (123.123)"], [4: "Four (123.1234)"] ]

		//Integer count=0
//	TODO
		if(dataSources){
			for(Map ent in dataSources){

				String sid=sMs(ent,sID)
				String attribute=sMs(ent,sA)
				String rid=ent.rid.toString()
				String dn=sMs(ent,sDISPNM)
				String typ=((String)ent[sT]).capitalize()
				String hint= typ=='Fuel' ? " (Canister ${ent.c} Name ${ent.n})" : sBLK
				String sa="${sid}_${attribute}".toString()

				container=[]
				hubiForm_section("${sLblTyp(ent[sT])}${dn} - ${attribute}${hint}", 1, "directions", sid+attribute){

					if(typ=='Sensor'){
						String tvar="var_"+sa+"_lts"
						if((Boolean)parent.ltsAvailable(rid, attribute)){
							container=[]
							container << hubiForm_sub_section("Long Term Storage in use")
							hubiForm_container(container, 1)

						}else{
							app.updateSetting(tvar, false)
							settings[tvar]= false
						}
					}

					input( (sTYPE): sENUM, (sNM): "attribute_"+sa+"_decimals",
							(sTIT): "<b>Number of Decimal Places to Display</b>",
							multiple: false, required: false, options: decimalsEnum,
							defaultValue: 1)

					container << hubiForm_text_input("<b>Scale Factor for Values</b><br><small>Example: To scale down by 10X, input 0.1<br>Leave as <b>1</b> for unchanged</small>",
							"attribute_"+sa+"_scale",
							s1, false)

					container << hubiForm_text_input("<b>Override ${typ} Name on Graph</b><small></i><br>Use %deviceName% for DEVICE and %attributeName% for ATTRIBUTE</i></small>",
							"graph_name_override_"+sa,
							"%deviceName%: %attributeName%", false)
					container << hubiForm_color	("Bar Background",		"attribute_"+sa+"_background", "#3e4475", false, true)
					container << hubiForm_color	("Bar Border",			"attribute_"+sa+"_current_border", "#FFFFFF", false)

					container << hubiForm_slider	((sTIT): "Bar Opacity",
							(sNM): "attribute_"+sa+"_opacity",
							default: 100, min: 1, max: 100, units: "%")

					container << hubiForm_line_size ((sTIT): "Bar Border",
							(sNM): "attribute_"+sa+"_current_border",
							default: 2, min: 1, max: 10)

					container << hubiForm_switch ([(sTIT): "Show Current Value on Bar",
							(sNM): "attribute_"+sa+"_show_value",
							default: false,
							submit_on_change: true])

					if(settings["attribute_"+sa+"_show_value"]==true){
						container<< hubiForm_text_input("Units", "attribute_"+sa+"_annotation_units", sBLK, false)
					}
					hubiForm_container(container, 1)
				}
			}
		}
	}
}

def graphBar(){

	dynamicPage(name: "graphSetupPage"){
		List container
		hubiForm_section("General Options", 1, sBLK, sBLK){
			container=[]
			input( (sTYPE): sENUM, (sNM): "graph_type",(sTIT): "<b>Select graph type</b>", multiple: false, required: false, options: [["1": "Bar Chart"],["2": "Column Chart"]], defaultValue: s1)

			inputGraphUpdateRate()

			container << hubiForm_color ("Graph Background", "graph_background", "#FFFFFF", false)
			container << hubiForm_slider ((sTIT): "Graph Bar Width (1%-100%)", (sNM): "graph_bar_percent", default: 90, min: 1, max: 100, units: "%")
			container << hubiForm_text_input("Graph Max", "graph_max", sBLK, false)
			container << hubiForm_text_input("Graph Min", "graph_min", sBLK, false)

			hubiForm_container(container, 1)
		}

		hubiForm_section("Axes", 1, sBLK, sBLK){
			container=[]
			container << hubiForm_color ("Axis", "haxis", "#000000", false)
			container << hubiForm_font_size ((sTIT): "Axis", (sNM): "haxis", default: 9, min: 2, max: 20)
			container << hubiForm_slider ((sTIT): "Number of Pixels for Axis", (sNM): "graph_h_buffer", default: 40, min: 10, max: 500, units: " pixels")
			hubiForm_container(container, 1)
		}
		hubiForm_section("Device Names", 1, sBLK, sBLK){
			container=[]
			container << hubiForm_font_size ((sTIT): "Device Name", (sNM): "graph_axis", default: 9, min: 2, max: 20)
			container << hubiForm_color ("Device Name","graph_axis", "#000000", false)
			container << hubiForm_slider ((sTIT): "Number of Pixels for Device Name Area", (sNM): "graph_v_buffer", default: 100, min: 10, max: 500, units: " pixels")

			hubiForm_container(container, 1)
		}
		hubiForm_section("Graph Size", 1, sBLK, sBLK){
			container=[]
			input( (sTYPE): sBOOL, (sNM): "graph_static_size",(sTIT): "<b>Set size of Graph?</b><br><small>(False=Fill Window)</small>", defaultValue: false, submitOnChange: true)
			if((Boolean)settings.graph_static_size){
				container << hubiForm_slider ((sTIT): "Horizontal dimension of the graph", (sNM): "graph_h_size", default: 800, min: 100, max: 3000, units: " pixels")
				container << hubiForm_slider ((sTIT): "Vertical dimension of the graph", (sNM): "graph_v_size", default: 600, min: 100, max: 3000, units: " pixels")
			}

			hubiForm_container(container, 1)
		}
		hubiForm_section("Annotations", 1, sBLK, sBLK){
			container=[]
			container << hubiForm_font_size ((sTIT): "Annotation", (sNM): "annotation", default: 16, min: 2, max: 40)
			container << hubiForm_switch	([(sTIT): "Show Annotation Outside (true) or Inside (false) of Bars", (sNM): "annotation_inside", default:false])
			container << hubiForm_color	("Annotation", "annotation", "#FFFFFF", false)
			container << hubiForm_color	("Annotation Aura", "annotation_aura", "#000000", false)
			container << hubiForm_switch	([(sTIT): "Bold Annotation", (sNM): "annotation_bold", default:false])
			container << hubiForm_switch	([(sTIT): "Italic Annotation", (sNM): "annotation_italic", default:false])

			hubiForm_container(container, 1)
		}
	}
}

String getData_bar(){
	Map resp=[:]

//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)

			resp[sid]= resp[sid] ?: [:]

			Map a= gtFloatMap(ent)
			if(a)
				resp[sid][attribute]=a
			else
				resp[sid][attribute]=[current: 1.0, (sDT): new Date()]
		}
	}
	return JsonOutput.toJson(resp)
}

Map getOptions_bar(){

	List colors=[]
//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)
			String sa="${sid}_${attribute}".toString()

			String attrib_string="attribute_"+sa+"_color"
			String transparent_attrib_string="attribute_"+sa+"_color_transparent"
			colors << (settings[transparent_attrib_string] ? "transparent" : settings[attrib_string])
		}
	}

	String axis1,axis2
	Boolean gt1= (graph_type==s1)
	axis1= gt1 ? "hAxis" : "vAxis"
	axis2= gt1 ? "vAxis" : "hAxis"

	Map options=[
		"graphUpdateRate": Integer.parseInt(graph_update_rate),
		(sGRAPHT): Integer.parseInt(graph_type),
		"graphOptions": [
			"bar" : [ "groupWidth" : "${graph_bar_percent}%", ],
			"width": graph_static_size ? graph_h_size : "100%",
			"height": graph_static_size ? graph_v_size: "90%",
			"timeline": [
				"rowLabelStyle": ["fontSize": graph_axis_font, "color": graph_axis_color_transparent ? "transparent" : graph_axis_color],
				"barLabelStyle": ["fontSize": graph_axis_font]
			],
			"backgroundColor": graph_background_color_transparent ? "transparent" : graph_background_color,
			"isStacked": false,
			"chartArea": [
				"left": gt1 ? graph_v_buffer : graph_h_buffer,
				"right" : 10,
				"top": 10,
				"bottom": gt1 ? graph_h_buffer : graph_v_buffer ],
			"legend" : [ "position" : sNONE ],
			(axis1): [ "viewWindow" :
							["max" : graph_max,
								"min" : graph_min],
						"minValue" : graph_min,
						"maxValue" : graph_max,
						"textStyle" : ["color": haxis_color_transparent ? "transparent" : haxis_color, "fontSize": haxis_font]
			],
			(axis2): [ "textStyle" :
						["color": graph_axis_color_transparent ? "transparent" : graph_axis_color, "fontSize": graph_axis_font]
			],
			"annotations" : [
					"alwaysOutside": annotation_inside,
					"textStyle": [
								"fontSize": annotation_font,
								"bold":	annotation_bold,
								"italic": annotation_italic,
								"color":	annotation_color_transparent ? "transparent" : annotation_color,
								"auraColor":annotation_aura_color_transparent ? "transparent" : annotation_aura_color,
					],
					"stem": [ "color": "transparent" ],
					"highContrast": "false"
				],

			],
		"graphLow": graph_min,
		"graphHigh": graph_max,
	]

	return options
}

String getGraph_bar(){
	String fullSizeStyle="margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden"

	String html="""
<html style="${fullSizeStyle}">
	<head>
${scriptIncludes()}
		<script type="text/javascript">
google.charts.load('current',{'packages':['corechart']});

let options=[];
let subscriptions={};
let graphData={};

//stack for accumulating points to average
let stack={};

let websocket;

class Loader{
	constructor(){
		this.elem=jQuery(jQuery(document.body).prepend(`
			<div class="loaderContainer">
				<div class="dotsContainer">
					<div class="dot"></div>
					<div class="dot"></div>
					<div class="dot"></div>
				</div>
				<div class="text"></div>
			</div>
		`).children()[0]);
	}

	setText(text){
		this.elem.find('.text').text(text);
	}

	remove(){
		this.elem.remove();
	}
}

function getOptions(){
	return jQuery.get("${state.localEndpointURL}getOptions/?access_token=${state.endpointSecret}", (data) =>{
		options=data;
		console.log("Got Options");
		console.log(options);
	});
}

function getSubscriptions(){
	return jQuery.get("${state.localEndpointURL}getSubscriptions/?access_token=${state.endpointSecret}", (data) =>{
		console.log("Got Subscriptions");
		console.log(data);
		subscriptions=data;

	});
}

function getGraphData(){
	return jQuery.get("${state.localEndpointURL}getData/?access_token=${state.endpointSecret}", (data) =>{
		console.log("Got Graph Data");
		console.log(data);
		graphData=data;
	});
}

function parseEvent(event){
	let odeviceId=event.deviceId;
	let deviceId="d"+odeviceId;

	//only accept relevent events
	if(subscriptions.ids.includes(deviceId) && subscriptions.attributes[deviceId].includes(event.name)){
		let value=event.value;
		let attribute=event.name;

		console.log("Got Name: ", attribute, "Value: ", value);

		graphData[sdeviceId][attribute].current=value;
		graphData[sdeviceId][attribute].date=new Date();
		//update if we are realtime
		if(options.graphUpdateRate === 0) update();
	}
}

async function aupdate(){
	await getGraphData();
	drawChart();
}

function update(callback){
	drawChart(callback);
}

async function onLoad(){
	//append our css
	jQuery(document.head).append(`
		<style>
			.loaderContainer{
				position: fixed;
				z-index: 100;

				width: 100%;
				height: 100%;

				background-color: white;

				display: flex;
				flex-flow: column nowrap;
				justify-content: center;
				align-items: middle;
			}

			.dotsContainer{
				height: 60px;
				padding-bottom: 10px;

				display: flex;
				flex-flow: row nowrap;
				justify-content: center;
				align-items: flex-end;
			}

			@keyframes bounce{
				0%{
					transform: translateY(0);
				}

				50%{
					transform: translateY(-50px);
				}

				100%{
					transform: translateY(0);
				}
			}

			.dot{
				box-sizing: border-box;

				margin: 0 25px;

				width: 10px;
				height: 10px;

				border: solid 5px black;
				border-radius: 5px;

				animation-name: bounce;
				animation-duration: 1s;
				animation-iteration-count: infinite;
			}

			.dot:nth-child(1){
				animation-delay: 0ms;
			}

			.dot:nth-child(2){
				animation-delay: 333ms;
			}

			.dot:nth-child(3){
				animation-delay: 666ms;
			}

			.text{
				font-family: Arial;
				font-weight: 200;
				font-size: 2rem;
				text-align: center;
			}
		</style>
	`);

	let loader=new Loader();

	//first load
	loader.setText('Getting options (1/4)');
	await getOptions();
	loader.setText('Getting device data (2/4)');
	await getSubscriptions();
	loader.setText('Getting events (3/4)');
	await getGraphData();
	loader.setText('Drawing chart (4/4)');

	update(() =>{
		//destroy loader when we are done with it
		loader.remove();
	});

	if(subscriptions.id=='poll'){
		if(options.graphUpdateRate > 0){
			setInterval(() =>{
				aupdate();
			}, options.graphUpdateRate);
		}
	} else{
		//start our update cycle
		if(options.graphUpdateRate !== -1){
			//start websocket
			websocket=new WebSocket("ws://" + location.hostname + "/eventsocket");
			websocket.onopen=() =>{
				console.log("WebSocket Opened!");
			}
			websocket.onmessage=(event) =>{
				parseEvent(JSON.parse(event.data));
			}

			if(options.graphUpdateRate !== 0){
				setInterval(() =>{
					update();
				}, options.graphUpdateRate);
			}
		}
	}

	//attach resize listener
	window.addEventListener("resize", () =>{
		drawChart();
	});
}

function onBeforeUnload(){
	if(websocket) websocket.close();
}

function formatValue(val, opts){
	val=val * parseFloat(opts.scale);
	return val.toFixed(opts.decimals);
}

function drawChart(callback){
	let now=new Date().getTime();
	let min=now - options.graphTimespan;
	const date_options={
		weekday: "long",
		year: "numeric",
		month:"long",
		day:"numeric"
	};
	const time_options ={
		hour12 : true,
		hour: "2-digit",
		minute: "2-digit",
		second: "2-digit"
	};

	const dataTable=new google.visualization.arrayToDataTable([[{ type: 'string', label: 'Device' },{ type: 'number', label: 'Value'},{ role: "style" },{ role: "tooltip" },{ role: "annotation" },]]);

	subscriptions.order.forEach(orderStr =>{
		const splitStr=orderStr.split('_');
		const deviceId=splitStr[1];
		const attr=splitStr[2];
		const event=graphData[deviceId][attr];
		const cur_=parseFloat(event.current);
		var cur_String='';
		var units_=``;

		var t_date=new Date(event.date);
		var date_String=t_date.toLocaleDateString("en-US",date_options);
		var time_String=t_date.toLocaleTimeString("en-US",time_options);

		const name=subscriptions.labels[deviceId][attr].replace('%deviceName%', subscriptions.sensors[deviceId].displayName).replace('%attributeName%', attr);
		const colors=subscriptions.colors[deviceId][attr];
		if(colors.showAnnotation == true){
			cur_String=`\${formatValue(cur_, colors)}\${colors.annotation_units} `;
			units_=`\${colors.annotation_units}`;
		}

		var stats_=`\${name}\nCurrent: \${event.current}\${units_}\nDate: \${date_String} \${time_String}`

		dataTable.addRow([name, cur_, `{color:		\${colors.backgroundColor};
								stroke-color: \${colors.currentValueBorderColor};
								fill-opacity: \${colors.opacity};
								stroke-width: \${colors.currentValueBorderLineSize};}`,
								`\${stats_}`,
								`\${cur_String} `]);

	});

	var chart;

	if(options.graphType == 1){
		chart=new google.visualization.BarChart(document.getElementById("timeline"));
	} else{
		chart=new google.visualization.ColumnChart(document.getElementById("timeline"));
	}
	//if we have a callback
	if(callback) google.visualization.events.addListener(chart, 'ready', callback);

	chart.draw(dataTable, options.graphOptions);
}

google.charts.setOnLoadCallback(onLoad);
window.onBeforeUnload=onBeforeUnload;
		</script>
	</head>
	<body style="${fullSizeStyle}">
		<div id="timeline" style="${fullSizeStyle}" align="center"></div>
	</body>
</html>
"""

	return html
}

//oauth endpoints

Map getSubscriptions_bar(){
	List _ids=[]
	Map _attributes=[:]
	Map labels=[:]
	Map colors=[:]

	Boolean isPoll
	isPoll=(Boolean)state.hasFuel

//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)
			String sa="${sid}_${attribute}".toString()

			_ids << sid

			_attributes[sid]=[]
			labels[sid]=[:]
			colors[sid]=[:]

			_attributes[sid] << attribute
			labels[sid][attribute]=settings["graph_name_override_"+sa]
			colors[sid][attribute]=[
					"backgroundColor":			settings["attribute_"+sa+"_background_color"],
					"currentValueColor":		settings["attribute_"+sa+"_current_color"],
					"currentValueBorderColor":	settings["attribute_"+sa+"_current_border_color"],
					"currentValueBorderLineSize": settings["attribute_"+sa+"_current_border_line_size"],
					"showAnnotation":			settings["attribute_"+sa+"_show_value"],
					"annotation_font":			settings["attribute_"+sa+"_annotation_font"],
					"annotation_font_size":		settings["attribute_"+sa+"_annotation_font_size"],
					"annotation_color":			settings["attribute_"+sa+"_annotation_color"],
					"annotation_units":			settings["attribute_"+sa+"_annotation_units"],
					"opacity":					settings["attribute_"+sa+"_opacity"]/100.0,
					"scale":					settings["attribute_"+sa+"_scale"],
					"decimals":					settings["attribute_"+sa+"_decimals"]
			]
		}
	}

	Map sensors_fmt=gtSensorFmt()

	List order=graph_order ? parseJson(graph_order) : []

	Map subscriptions=[
			(sID): isPoll ? 'poll' : 'sensor',
			"sensors": sensors_fmt,
			"ids": _ids,
			"attributes": _attributes,
			"labels": labels,
			"colors": colors,
			"order": order,
			"graphUpdateRate": Integer.parseInt(graph_update_rate),
	]

	return subscriptions
}









@Field static Map<String,Map<String,String>> supportedTypes=[
		"alarm":		["start": "on", "end": "off"],
		"contact":		["start": "open", "end": "closed"],
		"switch":		["start": "on", "end": "off"],
		"motion":		["start": "active", "end": "inactive"],
		"mute":			["start": "muted", "end": "unmuted"],
		"presence":		["start":"present", "end":"not present"],
		"holdableButton": ["start":"true", "end":"false"],
		"carbonMonoxide": ["start":"detected", "end":"clear"],
		"playing":		["start":"playing", "end":"stopped"],
		"door":			["start": "open", "end": "closed"],
		"speed":		["start": "on", "end": "off"],
		"lock":			["start": "unlocked", "end": "locked"],
		"shock":		["start": "detected", "end": "clear"],
		"sleepSensor":	["start": "sleeping", "end": "not sleeping"],
		"smoke":		["start":"detected", "end":"clear"],
		"sound":		["start":"detected", "end":"not detected"],
		"tamper":		["start":"detected", "end":"clear"],
		"valve":		["start": "open", "end": "closed"],
		"camera":		["start": "on", "end": "off"],
		"water":		["start": "wet", "end": "dry"],
		"windowShade":	["start": "open", "end": "closed"],
		"acceleration":	["start": "inactive", "end": "active"]
]

@Field static List<String> startTypes=['on',  'open',     'active', 'muted',    'present',    'true',  'detected', 'playing', 'unlocked', 'sleeping',                      'wet']
@Field static List<String> endTypes=  ['off', 'closed', 'inactive', 'unmuted', 'not present', 'false', 'clear',     'stopped', 'locked',   'not sleeping', 'not detected', 'dry']


Map gtStartEndTypes(Map ent, String attribute){
	String defltS, defltE
	defltS=sBLK; defltE=sBLK
	if(supportedTypes.containsKey(attribute)){
		defltS=supportedTypes[attribute].start
		defltE=supportedTypes[attribute].end
		return [start: defltS, end: defltE]
	}else{
		//figure out from data if there are choices
		List<Map> fdata= gtDataSourceData(ent)
		Integer sz
		sz = fdata.size()
		if(sz>i1){
			// [date: date, (sVAL): v, t: t]
			def val
			Integer i
			i=i2
			while(i>iZ){
				val= fdata[sz-i][sVAL]
				if(val && val instanceof String){
					String s= val
					if(!defltS && s in startTypes) defltS= s
					else if(!defltE && s in endTypes) defltE = s
					if(defltE && defltS) return [start: defltS, end: defltE]
				}
				i-=i1
				if(i==iZ){ defltS=sBLK; defltE=sBLK }
			}
		}
	}
	return null
}



/**  Timespans as MS */
@Field static List<Map> timespanEnum=[
		["60000":"1 Minute"], ["3600000":"1 Hour"], ["43200000":"12 Hours"],
		["86400000":"1 Day"], ["259200000":"3 Days"], ["604800000":"1 Week"]
]









/*
 * TODO: Timeline methods
 */

def mainTimeline(){
	mainShare1( """Choose Numeric Attributes or common sensor attributes (like on/off, open/close, present/not present,
								detected/clear, active/inactive, wet/dry)""" ,'graph_update_rate')
}

def deviceTimeline(){
	deviceShare1()
}

def attributeTimeline(){
	List<Map> dataSources= createDataSources(true)

	//state.count_=0
	dynamicPage(name: "attributeConfigurationPage", nextPage:"graphSetupPage"){
		List container
		hubiForm_section("Directions", 1, "directions", sBLK){
			container=[]
			container << hubiForm_text("""Configure what counts as a 'start' or 'end' event for each attribute on the timeline.
									For example, Switches start when they are 'on' and end when they are 'off'.\n\nSome attributes will automatically populate.
									You can change them if you have a different configuration (chances are you won't).
									Additionally, for devices with numeric values, you can define a range of values that count as 'start' or 'end'.
									For example, to select all the times a temperature is above 70.5 degrees fahrenheit, you would set the start to '> 70.5', and the end to '< 70.5'.
									Supported comparators are: '<', '>', '<=', '>=', '==', '!='.\n\nBecause we are dealing with HTML, '<' is abbreviated to &amp;lt; after you save. That is completely normal. It will still work.""" )

			hubiForm_container(container, 1)

		}
		hubiForm_section("Graph Order", 1, "directions", sBLK){
			hubiForm_list_reorder("graph_order", "line")
		}

//	TODO
		Integer cnt
		cnt=0
		if(dataSources){
			for(Map ent in dataSources){

				//state.count_++
				String sid=sMs(ent,sID)
				String attribute=sMs(ent,sA)
				String rid=ent.rid.toString()
				String dn=sMs(ent,sDISPNM)
				String typ=((String)ent[sT]).capitalize()
				String hint= typ=='Fuel' ? " (Canister ${ent.c} Name ${ent.n})" : sBLK
				String sa="${sid}_${attribute}".toString()

				hubiForm_section("${sLblTyp(ent[sT])}${dn} - ${attribute}${hint}", 1, "directions", sid+attribute){
					container=[]

					if(typ=='Sensor'){
						String tvar="var_"+sa+"_lts"
						if((Boolean)parent.ltsAvailable(rid, attribute)){
							container=[]
							container << hubiForm_sub_section("Long Term Storage in use")

						}else{
							app.updateSetting(tvar, false)
							settings[tvar]= false
						}
					}

					container << hubiForm_text_input("Override ${typ} Name on Graph<small></i><br>Use %deviceName% for DEVICE and %attributeName% for ATTRIBUTE</i></small>",
						"graph_name_override_${sa}",
						"%deviceName%: %attributeName%", false)

					String defltS, defltE
					defltS=sBLK; defltE=sBLK
					Map a=gtStartEndTypes(ent,attribute)
					if(a){
						defltS=a.start ?: sBLK
						defltE=a.end ?: sBLK
					}

					container << hubiForm_color	("Line",	"attribute_"+sa+"_line", hubiTools_rotating_colors(cnt), false, false)
					container << hubiForm_text_input ("Start event value",	"attribute_"+sa+"_start", defltS, false)
					container << hubiForm_text_input ("End event value",	"attribute_"+sa+"_end", defltE, false)
					hubiForm_container(container, 1)
				}
				cnt += 1
			}
		}
	}
}

def graphTimeline(){

	List<Map> lOpts= [["0":"Never"], ["10000":"10 Seconds"], ["30000":"30 seconds"], ["60000":"1 Minute"], ["120000":"2 Minutes"], ["180000":"3 Minutes"], ["240000":"4 Minutes"], ["300000":"5 Minutes"], ["600000":"10 Minutes"],
			 ["1200000":"20 Minutes"], ["1800000":"30 Minutes"], ["3600000":"1 Hour"], ["6400000":"2 Hours"], ["9600000":"3 Hours"], ["13200000":"4 Hours"], ["16800000":"5 Hours"], ["20400000":"6 Hours"]]

	dynamicPage(name: "graphSetupPage"){
		List container
		hubiForm_section("General Options", 1, "directions", sBLK){
			inputGraphUpdateRate()
			input( (sTYPE): sENUM, (sNM): "graph_timespan",(sTIT): "<b>Select Time span to Graph</b>", multiple: false, required: false, options: timespanEnum, defaultValue: "43200000")
			input( (sTYPE): sENUM, (sNM): "graph_combine_rate",(sTIT): "<b>Combine events with events less than ? apart</b>", multiple: false, required: false, options: lOpts, defaultValue: s0)

			container=[]
			container << hubiForm_color ("Background", "graph_background", "#FFFFFF", false)

		}
		hubiForm_section("Graph Size", 1, sBLK, sBLK){
			container=[]
			input( (sTYPE): sBOOL, (sNM): "graph_static_size",(sTIT): "<b>Set size of Graph?</b><br><small>(False=Fill Window)</small>", defaultValue: false, submitOnChange: true)
			if((Boolean)settings.graph_static_size){
				container << hubiForm_slider ((sTIT): "Horizontal dimension of the graph", (sNM): "graph_h_size", default: 800, min: 100, max: 3000, units: " pixels", submit_on_change: false)
				container << hubiForm_slider ((sTIT): "Vertical dimension of the graph", (sNM): "graph_v_size", default: 600, min: 100, max: 3000, units: " pixels", submit_on_change: false)
			}

			hubiForm_container(container, 1)
		}
		hubiForm_section("Device Name Display", 1, sBLK, sBLK){
			container=[]
			container << hubiForm_color ("Device Text", "graph_axis", "#FFFFFF", false)
			container << hubiForm_font_size ((sTIT): "Device", (sNM): "graph_axis", default: 9, min: 2, max: 20)
			hubiForm_container(container, 1)
		}
	}
}

String getData_timeline(){
	Map resp=[:]
//	Date now=new Date()
	Date then
	then=new Date()

	Long graph_time
	use (TimeCategory){
		Double val=Double.parseDouble("${graph_timespan}")/1000.0
		then -= (val.toInteger()).seconds
		graph_time=then.getTime()
	}

//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)
			//String typ=((String)ent[sT]).capitalize()

			resp[sid]= resp[sid] ?: [:]

			List<Map> data=CgetData(ent, then)

//			log.warn "got sensor: $sensor attribute: $attribute	data1: $data"

			List<Map>data1=data.collect{ Map it-> [(sDT): it.t, (sVAL): "${it[sVAL]}".toString()]}

			resp[sid][attribute]=data1.findAll{ Map it-> (Long)it[sDT] > graph_time}

			List<Map> temp=([]+data1) as List<Map>
			//temp=temp.sort{ (Long)it.date }
			resp[sid][attribute]=temp

//			log.warn "FINAL got sensor: $sensor attribute: $attribute	data1: $temp"

		}
	}
	return JsonOutput.toJson(resp)
}

Map getOptions_timeline(){

	if(isEric())myDetail null,"getChartOptions_timeline",i1
	List colors=[]
	List<Map> order=hubiTools_get_order(graph_order)
	for(Map device in order){
		//String sa="${sid}_${attribute}".toString()
		String attrib_string="attribute_${device[sID]}_${device[sATTR]}_line_color"
		String transparent_attrib_string="attribute_${device[sID]}_${device[sATTR]}_line_color_transparent"
		colors << (settings[transparent_attrib_string] ? "transparent" : settings[attrib_string])
	}

	Map options=[
		"graphTimespan": Integer.parseInt(graph_timespan),
		"graphUpdateRate": Integer.parseInt(graph_update_rate),
		"graphCombine_msecs": Integer.parseInt(graph_combine_rate),
		"graphOptions": [
			"width": graph_static_size ? graph_h_size : "100%",
			"height": graph_static_size ? graph_v_size: "100%",
			"timeline": [
				"rowLabelStyle": ["fontSize": graph_axis_font, "color": graph_axis_color_transparent ? "transparent" : graph_axis_color],
				"barLabelStyle": ["fontSize": graph_axis_font],
			],
			"haxis" : [ "text": ["fontSize": "24px"]],
			"backgroundColor": graph_background_color_transparent ? "transparent" : graph_background_color,
			"colors" : colors
		],
	]

	if(isEric())myDetail null,"getChartOptions_timeline $options"

	return options
}

String getGraph_timeline(){
	String fullSizeStyle="margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden"

	String html="""
<html style="${fullSizeStyle}">
	<head>
${scriptIncludes1()}
		<script type="text/javascript">

//google.load("visualization", "1.1",{packages:["timeline"]});
google.charts.load('current',{'packages':['timeline']});
google.charts.setOnLoadCallback(onLoad);

let options=[];
let subscriptions={};
let graphData={};
let unparsedData={};

let websocket;

class Loader{
	constructor(){
		this.elem=jQuery(jQuery(document.body).prepend(`
			<div class="loaderContainer">
				<div class="dotsContainer">
					<div class="dot"></div>
					<div class="dot"></div>
					<div class="dot"></div>
				</div>
				<div class="text"></div>
			</div>
		`).children()[0]);
	}

	setText(text){
		this.elem.find('.text').text(text);
	}

	remove(){
		this.elem.remove();
	}
}

function getOptions(){
	return jQuery.get("${state.localEndpointURL}getOptions/?access_token=${state.endpointSecret}", (data) =>{
		console.log("Got Options");
		console.log(data);
		options=data;
	});
}

function getSubscriptions(){
	return jQuery.get("${state.localEndpointURL}getSubscriptions/?access_token=${state.endpointSecret}", (data) =>{
		console.log("Got Subscriptions");
		console.log(data);
		subscriptions=data;

	});
}

function getGraphData(){
	return jQuery.get("${state.localEndpointURL}getData/?access_token=${state.endpointSecret}", (data) =>{
		console.log("Got Graph Data");
		console.log(data);
		unparsedData=data;

		let now=new Date().getTime();
		let min=now;
		min -= options.graphTimespan;

		//parse data
		Object.entries(unparsedData).forEach(([id, allEvents]) =>{
			graphData[id]={};
			Object.entries(allEvents).forEach(([attribute, events]) =>{
		console.log("graphData reset");
		console.log(id);
		console.log(attribute);
				graphData[id][attribute]=[];
				const start_event=subscriptions.definitions[id][attribute].start;
		console.log(start_event);
				const end_event=subscriptions.definitions[id][attribute].end;
		console.log(end_event);

				const thisOut=graphData[id][attribute];
				var date;
				var seconds=options.graphCombine_msecs;
				var skip_trigger;
				if(events.length > 0){
					//if our first event is an end event, start at 1
					thisOut.push(evalTest(start_event, events[0].value) ?{ start: events[0].date } :{ end: events[0].date });
					for(let i=1; i < events.length; i++){
						const is_start=evalTest(start_event, events[i].value);
						const is_end=evalTest(end_event, events[i].value);

						//always add the first event
						if(is_end && !thisOut[thisOut.length - 1].end){
							thisOut[thisOut.length - 1].end=events[i].date;

						} else if(is_start && thisOut[thisOut.length - 1].end){
							/*TCH - Look for more than 5 minutes between events*/
							if(events[i].date - thisOut[thisOut.length - 1].end > seconds){
								thisOut.push({ start: events[i].date });
							} else{
								skip_trigger=true;
							}
						} else if (is_end && skip_trigger){
							thisOut[thisOut.length - 1].end=events[i].date;
							skip_trigger=false;
						}
					}
				}
				//if it's already on, add an event
				else if(evalTest(start_event, subscriptions.sensors[id].currentStates.find((it) => it.name == attribute).value)){
					thisOut.push({ start: min });
				}
			});
		});

		console.log("Parsed Data");
		console.log(Object.assign({}, graphData));
	});
}

function parseEvent(event){
	const now=new Date().getTime();

	let odeviceId=event.deviceId;
	let deviceId="d"+odeviceId;
	let attribute=event.name;

	//only accept relevent events
	if(Object.keys(subscriptions.sensors).includes("" + deviceId) && Object.keys(subscriptions.definitions[deviceId]).includes(attribute)){
		const pastEvents=graphData[deviceId][attribute];
		if(pastEvents.length > 0){
			const start_event=subscriptions.definitions[deviceId][attribute].start;
			const end_event=subscriptions.definitions[deviceId][attribute].end;
			const is_start=evalTest(start_event, event.value);
			const is_end=evalTest(end_event, event.value);

			if(is_end && !pastEvents[pastEvents.length - 1].end) pastEvents[pastEvents.length - 1].end=now;
			else if(is_start && pastEvents[pastEvents.length - 1].end) pastEvents.push({ start: now });
		} else{
			pastEvents.push({ start: now });
		}

		//update if we are realtime
		if(options.graphUpdateRate === 0) update();
	}
}

function evalTest(evalStrPre, value){
	const evalStr=he.decode(evalStrPre);
	const operatorMatch=evalStr.replace(' ', '').match(/(<=)|(>=)|<|>|(==)|(!=)/g);

	if(operatorMatch){
		const operator=operatorMatch[0];
		const rest=parseFloat(evalStr.replace(operator, ''));
		const floatValue=parseFloat(value);

		switch (operator){
			case '<':
				return floatValue < rest;
			case '>':
				return floatValue > rest;
			case '==':
				return floatValue == rest;
			case '!=':
				return floatValue != rest;
			case '<=':
				return floatValue <= rest;
			case '>=':
				return floatValue >= rest;
			default:

		}
	} else{
		return value == evalStr;
	}
}

async function aupdate(){
	await getGraphData();
	//drawChart();
	update();
}

async function update(callback){
	let now=new Date().getTime();
	let min=now;
	min -= options.graphTimespan;

	//parse data

	//boot old data
	Object.entries(graphData).forEach(([id, allEvents]) =>{
		Object.entries(allEvents).forEach(([attribute, events]) =>{
		//shift left points and mark for deletion if applicable
			let newArr=events.map(it =>{
				let ret={ ...it }

				if(it.end && it.end < min){
					ret={};
				}
				else if(it.start && it.start < min) ret.start=min;

				return ret;
			});

			//delete non-existant nodes
			newArr=newArr.filter(it => it.start || it.end);

			//merge events
			let mergedArr=[];

			newArr.forEach((event, index) =>{
				if(index === 0) mergedArr.push(event);
				else{
					if(event.start - mergedArr[mergedArr.length - 1].end <= options.graphCombine_msecs){
						mergedArr[mergedArr.length - 1].end=event.end;
					} else mergedArr.push(event);
				}
			});

			graphData[id][attribute]=mergedArr;
		});
	});

	drawChart(now, min, callback);
}

async function onLoad(){
	//append our css
	jQuery(document.head).append(`
		<style>
			.loaderContainer{
				position: fixed;
				z-index: 100;

				width: 100%;
				height: 100%;

				background-color: white;

				display: flex;
				flex-flow: column nowrap;
				justify-content: center;
				align-items: middle;
			}

			.dotsContainer{
				height: 60px;
				padding-bottom: 10px;

				display: flex;
				flex-flow: row nowrap;
				justify-content: center;
				align-items: flex-end;
			}

			@keyframes bounce{
				0%{
					transform: translateY(0);
				}

				50%{
					transform: translateY(-50px);
				}

				100%{
					transform: translateY(0);
				}
			}

			.dot{
				box-sizing: border-box;

				margin: 0 25px;

				width: 10px;
				height: 10px;

				border: solid 5px black;
				border-radius: 5px;

				animation-name: bounce;
				animation-duration: 1s;
				animation-iteration-count: infinite;
			}

			.dot:nth-child(1){
				animation-delay: 0ms;
			}

			.dot:nth-child(2){
				animation-delay: 333ms;
			}

			.dot:nth-child(3){
				animation-delay: 666ms;
			}

			.text{
				font-family: Arial;
				font-weight: 200;
				font-size: 2rem;
				text-align: center;
				}
		</style>
	`);

	let loader=new Loader();

	//first load
	loader.setText('Getting options (1/4)');
	await getOptions();
	loader.setText('Getting device data (2/4)');
	await getSubscriptions();
	loader.setText('Getting events (3/4)');
	await getGraphData();

	loader.setText('Drawing chart (4/4)');

	//update data
	update(() =>{
		//destroy loader when we are done with it
		loader.remove();
	});

	if(subscriptions.id=='poll'){
		if(options.graphUpdateRate > 0){
			setInterval(() =>{
				aupdate();
			}, options.graphUpdateRate);
		}
	} else{
		//start our update cycle
		if(options.graphUpdateRate !== -1){
			//start websocket
			websocket=new WebSocket("ws://" + location.hostname + "/eventsocket");
			websocket.onopen=() =>{
				console.log("WebSocket Opened!");
			}
			websocket.onmessage=(event) =>{
				parseEvent(JSON.parse(event.data));
			}

			if(options.graphUpdateRate !== 0){
				setInterval(() =>{
					update();
				}, options.graphUpdateRate);
			}
		}
	}

	//attach resize listener
	window.addEventListener("resize", () =>{
		let now=new Date().getTime();
		let min=now;
		min -= options.graphTimespan;

		drawChart(now, min);

	});

}

function getToolTip(name, start, end){
	var html =	"<div class='mdl-layout__header' style='display: block; background:#033673; width: 100%; padding-top:10px; padding-bottom:5px; overflow: hidden;'>";
	html +=		"<div class='mdl-layout__header-row'";
	html +=		"<span class='mdl-layout__title' style='font-size: 14px; color:#FFFFFF !important; width: auto; font-family:Roboto, Helvetica, Arial, sans-serif !important;'>";
	html +=		name;
	html +=		"</span>";
	html +=		"</div>";
	html +=		"</div>";

	html +=		"<div class='mdl-grid' style='padding: 5px; background:#FFFFFF; font-family:Roboto, Helvetica, Arial, sans-serif !important;'>"
	html +=		"<div class='mdl-cell mdl-cell--12-col-desktop mdl-cell--8-col-tablet mdl-cell--4-col-phone' style='margin-bottom: 5px; padding: 5px;' >";
	html=html+ start.toDateString()+" at "+start.toLocaleTimeString('en-US');
	html +=		"</div>";
	html +=		"<div class='mdl-cell mdl-cell--12-col-desktop mdl-cell--8-col-tablet mdl-cell--4-col-phone' style='margin-bottom: 5px; padding: 5px;'>";
	html=html+ end.toDateString()+" at "+end.toLocaleTimeString('en-US');
	html +=		"</div>";


	//var html="<p style='font-family:courier,arial,helvetica; font-size: 14px;'><b>"+name+"</b><br><hr><br>";
	//html +=	"Start: "+start.toDateString()+" at "+start.toLocaleTimeString('en-US')+"<br>";
	//html +=	"End: "+end.toDateString()+" at "+start.toLocaleTimeString('en-US')+"<br>";
	return html;
}

function drawChart(now, min, callback){
	let dataTable=new google.visualization.DataTable();
	dataTable.addColumn({ type: 'string', id: 'Device' });
	dataTable.addColumn({ type: 'date', id: 'Start' });
	dataTable.addColumn({ type: 'date', id: 'End' });
	dataTable.addColumn({ type: 'string', 'role': 'tooltip', 'p':{'html': true}});

	subscriptions.order.forEach(orderStr =>{
	const splitStr=orderStr.split('_');
	const id=splitStr[1];
	const attribute=splitStr[2];
	const events=graphData[id][attribute];

			let newArr=[...events];

		//add endpoints for orphans
		newArr=newArr.map((it) =>{
			if(!it.start){
				return{...it, start: min }
			}
			else if(!it.end) return{...it, end: now}
			return it;
		});

		//add endpoint buffers
		if(newArr.length == 0){
			newArr.push({ start: min, end: min });
			newArr.push({ start: now, end: now });
		} else{
			if(newArr[0].start != min) newArr.push({ start: min, end: min });
			if(newArr[newArr.length - 1].end != now) newArr.push({ start: now, end: now });
		}

		let name=subscriptions.sensors[id].displayName;

		dataTable.addRows(newArr.map((parsed) => [
			subscriptions.labels[id][attribute].replace('%deviceName%', name).replace('%attributeName%', attribute),
			moment(parsed.start).toDate(),
			moment(parsed.end).toDate(),
			getToolTip(
					subscriptions.labels[id][attribute].replace('%deviceName%', name).replace('%attributeName%', attribute),
					moment(parsed.start).toDate(),
					moment(parsed.end).toDate() )
		]));

	});

	let chart=new google.visualization.Timeline(document.getElementById("timeline"));

	//if we have a callback
	if(callback) google.visualization.events.addListener(chart, 'ready', callback);

	chart.draw(dataTable, options.graphOptions);

	google.visualization.events.addListener(chart, 'onmouseover', tooltipHandler);

	function tooltipHandler(e){
		if(e.row != null){
			jQuery(".google-visualization-tooltip").html(dataTable.getValue(e.row,3)).css({width:"auto",height:"auto"});
		}
	}

}
		</script>
	</head>
	<body style="${fullSizeStyle}">
		<div id="timeline" style="${fullSizeStyle}" align="center"></div>
	</body>
</html>
	"""

	return html
}

//oauth endpoints

Map getSubscriptions_timeline(){

	List _ids=[]
	Map definitions=[:]
	Map labels=[:]

	Boolean isPoll
	isPoll=(Boolean)state.hasFuel

//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)
			String sa="${sid}_${attribute}".toString()

			_ids << sid

			definitions[sid]=[:]
			labels[sid]=[:]
			definitions[sid][attribute]=["start": settings["attribute_${sa}_start"] ?: sSPC,
										 "end": settings["attribute_${sa}_end"] ?: sSPC ]
			labels[sid][attribute]=settings["graph_name_override_${sa}"]
		}
	}

	Map sensors_fmt=gtSensorFmt(true)

	List order=graph_order ? parseJson(graph_order) : []

	Map subscriptions=[
			(sID): isPoll ? 'poll' : 'sensor',
			"ids": _ids,
			"sensors": sensors_fmt,
			"definitions": definitions,
			"labels": labels,
			"order": order
	]
	return subscriptions
}








/*
 * TODO: Timegraph methods
 */

def mainTimegraph(){
	mainShare1('Choose Numeric Attributes only','graph_timespan')
}

def deviceTimegraph(){
//	wremoveSetting('graph_timespan')
	deviceShare1()
}

def attributeTimegraph(){
	attributeShare1()
}

def graphTimegraph(){

	List<Map> timespanEnum2=[
			["10":"10 Milliseconds"], ["1000":"1 Second"], ["5000":"5 Seconds"], ["30000":"30 Seconds"],
			["60000":"1 Minute"], ["120000":"2 Minutes"], ["300000":"5 Minutes"], ["600000":"10 Minutes"],
			["2400000":"30 minutes"], ["3600000":"1 Hour"], ["43200000":"12 Hours"],
			["86400000":"1 Day"], ["259200000":"3 Days"], ["604800000":"1 Week"], ["1209600000":"2 Weeks"],
			["2629800000":"1 Month"]]

	dynamicPage(name: "graphSetupPage"){

		List container
		container=[]

		hubiForm_section("General Options", 1, sBLK, sBLK){

			//input( (sTYPE): sENUM, (sNM): "graph_timespan",(sTIT): "<b>Select Time span to Graph</b>", multiple: false, required: true, options: timespanEnum, defaultValue: "43200000")
			input( (sTYPE): sENUM, (sNM): "graph_point_span",(sTIT): "<b>Integration Time</b><br><small>(The amount of time each data point covers)</small>",
					multiple: false, required: true, options: timespanEnum2, defaultValue: "300000", submitOnChange: true)

			inputGraphUpdateRate()
			input( (sTYPE): sENUM, (sNM): "graph_refresh_rate",(sTIT): "<b>Graph Update Rate</b></br><small>(For panel viewing; the refresh rate of the graph)</small>",
					multiple: false, required: true, options: rateEnum, defaultValue: "300000")

			container=[]

			container << hubiForm_sub_section("Graph Time Span<br><small>Amount of time the graph covers</small>")

			if(graph_timespan_weeks == null){
				app.updateSetting("graph_timespan_weeks", 0)
				app.updateSetting("graph_timespan_days", 1)
				app.updateSetting("graph_timespan_hours", 0)
				app.updateSetting("graph_timespan_minutes", 0)
				settings["graph_timespan_weeks"]=0
				settings["graph_timespan_days"]=1
				settings["graph_timespan_hours"]=0
				settings["graph_timespan_minutes"]=0
			}

			container << hubiForm_slider ((sTIT): "<b>Weeks</b>", (sNM): "graph_timespan_weeks",
					default: 0, min: 0, max: 104, units: " weeks", submit_on_change: true)

			container << hubiForm_slider ((sTIT): "<b>Days</b>", (sNM): "graph_timespan_days",
					default: 0, min: 0, max: 30, units: " days", submit_on_change: true)

			container << hubiForm_slider ((sTIT): "<b>Hours</b>", (sNM): "graph_timespan_hours",
					default: 0, min: 0, max: 24, units: " hours", submit_on_change: true)

			container << hubiForm_slider ((sTIT): "<b>Minutes</b>", (sNM): "graph_timespan_minutes",
					default: 0, min: 0, max: 60, units: " seconds", submit_on_change: true)

			Long msecs
			if(graph_timespan_weeks==null){
				msecs=86400000L
			}else{
				msecs= Math.round((Double)(graph_timespan_weeks)*604800000+
						(Double)(graph_timespan_days)*86400000+
						(Double)(graph_timespan_hours)*3600000+
						(Double)(graph_timespan_minutes)*60000)
			}

			app.updateSetting("graph_timespan", [(sTYPE): "number", (sVAL): msecs])
			settings["graph_timespan"]=msecs

			Integer points=graph_point_span ? (msecs/Double.parseDouble((String)graph_point_span)).toInteger() : 280

			if(points > 2000){
				container << hubiForm_text ("""<span style="color: red; font-weight: bold;">WARNING:</span> <b>${(points)} Points </b>will be generated per Attribute per Graph<br><small>Too many points will cause webCoRE graphs to hang or take a long time to generate</small>""")
			}else{
				container << hubiForm_text ("NOTE: <b>${(points)} Points </b>will be generated per Attribute per Graph")
			}

			container << hubiForm_sub_section("Other Options")

			container << hubiForm_color ("Graph Background",	"graph_background", "#FFFFFF", false)
			container << hubiForm_switch([(sTIT): "<b>Smooth Graph Points</b><br><small>(Enable Google Graph Smoothing)</small>", (sNM): "graph_smoothing", default: false])
			container << hubiForm_switch([(sTIT): "<b>Flip Graph to Vertical?</b><br><small>(Rotate 90 degrees)</small>", (sNM): "graph_y_orientation", default: false])
			container << hubiForm_switch([(sTIT): "<b>Reverse Data Order?</b><br><small> (Flip data left to Right)</small>", (sNM): "graph_z_orientation", default: false])

			hubiForm_container(container, 1)

		}

		hubiForm_section("Graph Title", 1, sBLK, sBLK){
			container=[]
			container << hubiForm_switch([(sTIT): "<b>Show Title on Graph</b>", (sNM): "graph_show_title", default: false, submit_on_change: true])
			if(graph_show_title==true){
				container << hubiForm_text_input ("<b>Graph Title</b>", "graph_title", "Graph Title", false)
				container << hubiForm_font_size ((sTIT): "Title", (sNM): "graph_title", default: 9, min: 2, max: 20)
				container << hubiForm_color	("Title", "graph_title", "#000000", false)
				container << hubiForm_switch	([(sTIT): "Graph Title Inside Graph?", (sNM): "graph_title_inside", default: false])
			}
			hubiForm_container(container, 1)
		}

		hubiForm_section("Graph Size", 1, sBLK, sBLK){
			container=[]

			container << hubiForm_switch	([(sTIT): "<b>Set Fill % of Graph?</b><br><small>(False=Default (80%) Fill)</small>",
											  (sNM): "graph_percent_fill", default: false, submit_on_change: true])
			if(graph_percent_fill==true){

				container << hubiForm_slider ((sTIT): "Horizontal fill % of the graph", (sNM): "graph_h_fill",
						default: 80, min: 1, max: 100, units: "%", submit_on_change: false)
				container << hubiForm_slider ((sTIT): "Vertical fill % of the graph", (sNM): "graph_v_fill",
						default: 80, min: 1, max: 100, units: "%", submit_on_change: false)

			}
			container << hubiForm_switch	([(sTIT): "<b>Set size of Graph?</b><br><small>(False=Fill Window)</small>",
											  (sNM): "graph_static_size", default: false, submit_on_change: true])
			if(graph_static_size==true){

				container << hubiForm_slider ((sTIT): "Horizontal dimension of the graph", (sNM): "graph_h_size",
						default: 800, min: 100, max: 3000, units: " pixels", submit_on_change: false)
				container << hubiForm_slider ((sTIT): "Vertical dimension of the graph", (sNM): "graph_v_size",
						default: 600, min: 100, max: 3000, units: " pixels", submit_on_change: false)
			}

			hubiForm_container(container, 1)
		}

		hubiForm_section("Horizontal Axis", 1, sBLK, sBLK){
			//Axis
			container=[]
			container << hubiForm_font_size ((sTIT): "Horizontal Axis", (sNM): "graph_haxis", default: 9, min: 2, max: 20)
			container << hubiForm_color	("Horizontal Header", "graph_hh", "#C0C0C0", false)
			container << hubiForm_color	("Horizontal Axis", "graph_ha", "#C0C0C0", false)
			container << hubiForm_text_input ("<b>Num Horizontal Gridlines</b><small> (Blank for auto)</small>", "graph_h_num_grid", sBLK, false)

			container+=  hubiForm_help()
			hubiForm_container(container, 1)
		}

		//Vertical Axis
		hubiForm_section("Vertical Axis", 1, sBLK, sBLK){
			container=[]
			container << hubiForm_font_size ((sTIT): "Vertical Axis", (sNM): "graph_vaxis", default: 9, min: 2, max: 20)
			container << hubiForm_color ("Vertical Header", "graph_vh", "#000000", false)
			container << hubiForm_color ("Vertical Axis", "graph_va", "#C0C0C0", false)
			hubiForm_container(container, 1)
		}

		//Left Axis
		List formatEnum=[["": "No Formatting ::: 12345"], ["decimal":"Decimal ::: 12,345"], ["short": "Short ::: 12K"], ["scientific": "Scientific ::: 1e5"], ["percent": "Percent ::: 1234500%"], ["long": "Long ::: 12 Thousand"]]

		hubiForm_section("Left Axis", 1, "arrow_back", sBLK){
			input( (sTYPE): sENUM, (sNM): "graph_vaxis_1_format",(sTIT): "<b>Number Format</b>", multiple: false, required: true, options: formatEnum, defaultValue: sBLK)
			container=[]
			container << hubiForm_text_input("<b>Minimum for left axis</b><small> (Blank for auto)</small>", "graph_vaxis_1_min", sBLK, false)
			container << hubiForm_text_input("<b>Maximum for left axis</b><small> (Blank for auto)</small>", "graph_vaxis_1_max", sBLK, false)
			container << hubiForm_text_input("<b>Num Vertical Gridlines</b><small> (Blank for auto)</small>", "graph_vaxis_1_num_lines", sBLK, false)
			container << hubiForm_switch	([(sTIT): "<b>Show Left Axis Label on Graph</b>", (sNM): "graph_show_left_label", default: false, submit_on_change: true])
			if(graph_show_left_label==true){
				container << hubiForm_text_input ("<b>Input Left Axis Label</b>", "graph_left_label", "Left Axis Label", false)
				container << hubiForm_font_size ((sTIT): "Left Axis", (sNM): "graph_left", default: 9, min: 2, max: 20)
				container << hubiForm_color	("Left Axis", "graph_left", "#FFFFFF", false)
			}
			hubiForm_container(container, 1)
		}

		//Right Axis
		hubiForm_section("Right Axis", 1, "arrow_forward", sBLK){
			input( (sTYPE): sENUM, (sNM): "graph_vaxis_2_format",(sTIT): "<b>Number Format</b>", multiple: false, required: true, options: formatEnum, defaultValue: sBLK)
			container=[]
			container << hubiForm_text_input("<b>Minimum for right axis</b><small> (Blank for auto)</small>", "graph_vaxis_2_min", sBLK, false)
			container << hubiForm_text_input("<b>Maximum for right axis</b><small> (Blank for auto)</small>", "graph_vaxis_2_max", sBLK, false)
			container << hubiForm_text_input("<b>Num Vertical Gridlines</b><small> (Blank for auto)</small>", "graph_vaxis_2_num_lines", sBLK, false)
			container << hubiForm_switch	([(sTIT): "<b>Show Right Axis Label on Graph</b>", (sNM): "graph_show_right_label", default: false, submit_on_change: true])
			if(graph_show_right_label==true){
				container << hubiForm_text_input("<b>Input right Axis Label</b>", "graph_right_label", "Right Axis Label", false)
				container << hubiForm_font_size ((sTIT): "Right Axis", (sNM): "graph_right", default: 9, min: 2, max: 20)
				container << hubiForm_color	("Right Axis", "graph_right", "#FFFFFF", false)
			}
			hubiForm_container(container, 1)
		}

		//Legend
		hubiForm_section("Legend", 1, sBLK, sBLK){
			container=[]
			List<Map> legendPosition=[["top": "Top"], ["bottom":"Bottom"], ["in": "Inside Top"]]
			List<Map> insidePosition=[["start": "Left"], ["center": "Center"], ["end": "Right"]]
			container << hubiForm_switch([(sTIT): "<b>Show Legend on Graph</b>", (sNM): "graph_show_legend", default: false, submit_on_change: true])
			if(graph_show_legend==true){
				container << hubiForm_font_size ((sTIT): "Legend", (sNM): "graph_legend", default: 9, min: 2, max: 20)
				container << hubiForm_color	("Legend", "graph_legend", "#000000", false)
				hubiForm_container(container, 1)
				input( (sTYPE): sENUM, (sNM): "graph_legend_position",(sTIT): "<b>Legend Position</b>", defaultValue: "Bottom", options: legendPosition)
				input( (sTYPE): sENUM, (sNM): "graph_legend_inside_position",(sTIT): "<b>Legend Justification</b>", defaultValue: "center", options: insidePosition)
			}else{
				hubiForm_container(container, 1)
			}

		}

		hubiForm_section("Current Value Overlay", 1, sBLK, sBLK){
			container=[]
			container << hubiForm_switch	([(sTIT): "<b>Show Current Values on Graph?</b>", (sNM): "show_overlay", default: false, submit_on_change: true])
			if(show_overlay == true){
				container << hubiForm_color	("Background", "overlay_background", "#000000", false)
				container << hubiForm_slider	((sTIT): "Background Opacity",
						(sNM): "overlay_background_opacity",
						default: 90,
						min: 0,
						max: 100,
						units: "%",
						submit_on_change: false)
				container << hubiForm_font_size ((sTIT): "Device", (sNM): "overlay", default: 12, min: 2, max: 40)
				container << hubiForm_color	("Device Text", "overlay_text", "#FFFFFF", false)

				List<String> horizontalAlignmentEnum=["Left", "Middle", "Right"]
				container << hubiForm_enum ((sTIT):	"Horizontal Placement",
						(sNM):	"overlay_horizontal_placement",
						list:	horizontalAlignmentEnum,
						default: "Right")

				List<String> verticalAlignmentEnum=["Top", "Middle", "Bottom"]
				container << hubiForm_enum ((sTIT):	"Vertical Placement",
						(sNM):	"overlay_vertical_placement",
						list:	verticalAlignmentEnum,
						default: "Top")

			}else{
				if(settings['overlay_background_color']!='#000000' || settings['overlay_background_opacity']!=90){
					app.updateSetting("overlay_background_color", [(sTYPE):'color', (sVAL):"#000000"])
					app.updateSetting("overlay_background_color_transparent", [(sTYPE):'bool', (sVAL):'false'])
					app.updateSetting("overlay_background_opacity", [(sTYPE):'number',(sVAL):90])
					app.updateSetting("overlay_background_font", [(sTYPE):'number',(sVAL): 12])
					app.updateSetting("overlay_text_color", [(sTYPE):'color', (sVAL):"#FFFFFF"])
					app.updateSetting("overlay_text_color_transparent", [(sTYPE):'bool', (sVAL):'false'])
					app.updateSetting("overlay_horizontal_placement", [(sTYPE):sENUM,(sVAL):"Right"])
					app.updateSetting("overlay_vertical_placement", [(sTYPE):sENUM,(sVAL):"Top"])
					wremoveSetting('overlay_order')
				}
			}
			container << hubiForm_sub_section("Display Order")
			hubiForm_container(container, 1)
			container=[]
			hubiForm_list_reorder("overlay_order", "background")
			//hubiForm_container(container, 1)
		}

/*		state.num_devices=0
		List availableAxis=[["0" : "Left Axis"], ["1": "Right Axis"]]
		if(state.num_devices == 1){
				availableAxis=[["0" : "Left Axis"], ["1": "Right Axis"], ["2": "Both Axes"]]
		}*/

		//Line
		Integer cnt; cnt=0
		//Boolean bar_size_shown=false

		//Deal with Global-Specific Settings (ie bar spacing and plot-point size)
		Boolean show_title,show_bar
		show_title=false
		show_bar=false
		//Boolean show_scatter=false
//	TODO
		List<Map> dataSources=gtDataSources()
		if(dataSources){
			for(Map ent in dataSources){

				String sid=sMs(ent,sID)
				String attribute=sMs(ent,sA)

				switch ((String)settings["graph_type_${sid}_${attribute}"]){
					//list:["Line", "Area", "Scatter", "Bar", "Stepped"],
					case "Bar"	: show_title=true; show_bar=true; break
				}
			}
		}
		if(show_title && show_bar){
			hubiForm_section("Overall Settings for Graph Types", 1, sBLK, sBLK){
				container=[]

				container << hubiForm_slider ((sTIT): "Bar Graphs:: Relative Width for Bars",
						(sNM): "graph_bar_width",
						default: 90,
						min: 0,
						max: 100,
						units: "%",
						submit_on_change: false)
				hubiForm_container(container, 1)
			}
		}else
			app.updateSetting('graph_bar_width', 90)

//	TODO
		if(dataSources){
			for(Map ent in dataSources){

				String sid=sMs(ent,sID)
				String rid=ent.rid.toString()
				String attribute=sMs(ent,sA)
				String dn=sMs(ent,sDISPNM)
				String typ=((String) ent[sT]).capitalize()
				String hint= typ=='Fuel' ? " (Canister ${ent.c} Name ${ent.n})" : sBLK
				String sa= "${sid}_${attribute}".toString()

				String asasn= "attribute_${sa}_states"

				hubiForm_section("${sLblTyp(ent[sT])}${dn} - ${attribute}${hint}", 1, "direction",sid+attribute){

					container=[]

					container << hubiForm_sub_section("Plot Options")

					container << hubiForm_enum ((sTIT):			"Plot Type",
							(sNM):			"graph_type_${sa}".toString(),
							list:			["Line", "Area", "Scatter", "Bar", "Stepped"],
							default:		"Line",
							submit_on_change: true)
					// TODO submit_on_change was commented out....

					container << hubiForm_enum ((sTIT):			"Time Integration Function",
							(sNM):			"var_${sa}_function".toString(),
							list:			["Average", "Min", "Max", "Mid", "Sum"],
							default:		"Average")

					container << hubiForm_enum ((sTIT):			"Axis Side",
							(sNM):			"graph_axis_number_${sa}".toString(),
							list:			["Left", "Right"],
							default:		"Left")

					String colorText,fillText
					colorText=""
					fillText="Fill"
					String graphType=sMs(settings,"graph_type_${sa}")
					switch (graphType){
						case "Line":
							colorText="Line"
							fillText=""
							break
						case "Area":
							colorText="Area Line"
							break
						case "Bar":
							colorText="Bar Border"
							break
						case "Scatter":
							colorText="Border"
							break
						case "Stepped":
							colorText="Line"
							break
						default:
							fillText=""
					}

					container << hubiForm_sub_section(colorText+" Options")

					container << hubiForm_color(colorText,
							"var_${sa}_stroke",
							hubiTools_rotating_colors(cnt),
							false)

					container << hubiForm_slider ((sTIT): colorText+" Opacity",
							(sNM): "var_${sa}_stroke_opacity",
							default: 90,
							min: 0,
							max: 100,
							units: "%",
							submit_on_change: false)

					container << hubiForm_line_size ((sTIT): colorText,
							(sNM): "var_${sa}_stroke",
							default: 2, min: 1, max: 20)

					if(graphType in ["Bar","Area","Stepped"]){

						container << hubiForm_sub_section(graphType+sSPC+fillText+" Options")

						container << hubiForm_color(fillText,
								"var_${sa}_fill",
								hubiTools_rotating_colors(cnt),
								false)

						container << hubiForm_slider ((sTIT): fillText+" Opacity",
								(sNM): "var_${sa}_fill_opacity",
								default: 90,
								min: 0,
								max: 100,
								units: "%",
								submit_on_change: false)
					}
					if(graphType in ["Scatter","Line","Area"]){

						container << hubiForm_sub_section("Data Points")

						if(graphType in ["Line","Area"]){
							container << hubiForm_switch([(sTIT): "<b>Display Data Points on Line?</b>", (sNM): "var_${sa}_line_plot_points", default: false, submit_on_change: true])
						}

						if(settings["var_${sa}_line_plot_points"] || graphType == "Scatter"){

							container << hubiForm_enum (
									(sTIT): "Point Type",
									(sNM): "var_${sa}_point_type".toString(),
									list: [ "Circle", "Triangle", "Square", "Diamond", "Star", "Polygon"],
									default: "Circle")

							container << hubiForm_slider ((sTIT): "Point Size",
									(sNM): "var_${sa}_point_size".toString(),
									default: 5,
									min: 0,
									max: 60,
									units: " points",
									submit_on_change: false)
							if(graphType == "Area"){
								container << hubiForm_text ("<b>*Note, Area Plots use the same fill setting for Points and Area (Above)")
							}else{
								container << hubiForm_color("Point Fill",
										"var_${sa}_fill",
										hubiTools_rotating_colors(cnt),
										false)

								container << hubiForm_slider ((sTIT): "Point Fill Opacity",
										(sNM): "var_${sa}_fill_opacity",
										default: 90,
										min: 0,
										max: 100,
										units: "%",
										submit_on_change: false)
							}
						}else{
							app.updateSetting ("var_${sa}_point_size", 0)
							settings["var_${sa}_point_size"]=0
						}
					}

					def currentAttribute, sensor
					currentAttribute=null

					Boolean enumType
					enumType=false

					List<String> possible_values; possible_values=null

					//TODO need to check if dataset is quanted, and based on quant type decide if values can be determined
					// check if data is regular start:
					String defltS, defltE
					Map b=gtStartEndTypes(ent,attribute)
					if(b){
						defltS=b.start
						defltE=b.end
						enumType=true
						possible_values = [defltS,defltE]
					}else{
						if(typ=='Sensor'){
							Boolean multiple=true
							String varn=multiple ? 'sensors' : 'sensor_' // have to get devices from settings
							def a=gtSetting(varn)
							List devs = multiple ? a : [a]
							if(devs.size()){
								sensor=devs.find{ it.id == rid }
								List sas= (List)sensor.getSupportedAttributes()
								for(attrib in sas){
									if((String)attrib[sNM] == attribute){
										currentAttribute=attrib
										if(attrib.dataType == "ENUM"){
											possible_values=currentAttribute.getValues()
											enumType=true
										}
									}
								}
							}else warn 'graphTimegraph: no devices found',null
						}
					}

					if(enumType){
						container << hubiForm_sub_section("""Numerical values for "$attribute" states""")

						for(String value in possible_values){
							container << hubiForm_text_input("Value for <mark>$value</mark>",
									"attribute_${sa}_${value}",
									"100",
									false)
						}
						app.updateSetting (asasn, possible_values)
					}

					if(!enumType){
						String csn= "attribute_${sa}_custom_states"
						Boolean cs
						cs= settings[csn]
						container << hubiForm_sub_section("""Custom State Values for "$attribute" """ )
						if(cs == null){
							app.updateSetting(csn, [(sTYPE): sBOOL, (sVAL): "false"])
						}
						container << hubiForm_switch([(sTIT): "<b>Set Custom State Values?</b><br><small>(For custom drivers w/ non-numeric values)</small>",
													  (sNM): csn,
													  default: false,
													  submit_on_change: true])

						cs= settings[csn]
						if(cs){

							//if(!settings["attribute_${sa}_num_custom_states"]){}

							container << hubiForm_text_input("<b>Number of Custom States</b>",
									"attribute_${sa}_num_custom_states",
									"2", true)

							Integer numStates=Integer.parseInt(settings["attribute_${sa}_num_custom_states"].toString())
							String csin
							Integer i
							for(i=0; i<numStates; i++){
								List subcontainer=[]
								csin= "attribute_${sa}_custom_state_${i}"

								subcontainer << hubiForm_text_input("<b>State #"+(i.toString())+"</b>",
										csin,
										sBLK,
										true)

								if(settings[csin]){

									subcontainer << hubiForm_text_input('<b>Value for "<mark>'+settings[csin]+'</mark></b>"',
											csin+"_value",
											s0,
											true)
								}
								container << hubiForm_subcontainer([objects: subcontainer, breakdown: [0.5, 0.5]])

							}

							//Update Settings

							possible_values=[]
							//Integer nums=Integer.parseInt(settings["attribute_${sa}_num_custom_states"].toString())
							for(i=0; i<numStates; i++){
								csin= "attribute_${sa}_custom_state_${i}"
								String csi= settings[csin]
								String csival= settings[csin+"_value"]
								if(csi && csival){
									String val=csi.replaceAll("\\s",sBLK)
									possible_values << val
									app.updateSetting("attribute_${sa}_${val}", csival)
								}
							}
							if(possible_values != []) app.updateSetting (asasn, possible_values)

						}else{
							List<String> asas= (List<String>)settings[asasn]
							if(asas){
								possible_values=asas
								for(String val in possible_values){
									app.updateSetting("attribute_${sa}_${val}".toString(),s0)
								}
								wremoveSetting(asasn)
							}
						}
					}

					//Line and Area Graphs can be "Drop-line"
					if((graphType in ["Line","Area","Stepped"]) && !enumType && settings["attribute_${sa}_custom_states"] == false){

						container << hubiForm_sub_section("Handle Missing Values")

						container << hubiForm_switch([(sTIT): "<b>Display Missing Data as a Drop Line?</b>", (sNM): "attribute_${sa}_drop_line", default: false, submit_on_change: true])

						if(settings["attribute_${sa}_drop_line"]==true){

							container << hubiForm_text_input("<b>Value of Missing Data</b>",
									"attribute_${sa}_drop_value",
									s0, false)
						}

						container << hubiForm_switch([(sTIT): "<b>Extend Left Value?</b><br><small>When values are unavailable, extend value to left</small>",
													  (sNM): "attribute_${sa}_extend_left", default: false, submit_on_change: false])

						container << hubiForm_switch([(sTIT): "<b>Extend Right Value?</b><br><small>When values are unavailable, extend value to right</small>",
													  (sNM): "attribute_${sa}_extend_right", default: false, submit_on_change: false])

					}

					container << hubiForm_sub_section("Restrict Displayed Values")

					container << hubiForm_switch([(sTIT): "<b>Restrict Displaying Bad Values?</b>", (sNM): "attribute_${sa}_bad_value", default: false, submit_on_change: true])

					if(settings["attribute_${sa}_bad_value"]==true){

						container << hubiForm_text_input("<b>Min Value to Exclude</b><br><small>If the recorded sensor value is <b>below</b> this value it will be dropped</small>",
								"attribute_${sa}_min_value",
								s0, false)

						container << hubiForm_text_input("<b>Max Value to Exclude</b><br><small>If the recorded sensor value is <b>above</b> this value it will be dropped</small>",
								"attribute_${sa}_max_value",
								"100", false)
					}

					container << hubiForm_text_input("<b>Units for Pretty Display</b>",
							"units_${sa}",
							sBLK,
							false)

					hubiForm_container(container, 1)
					cnt += 1
				}
			}
		}
	}
}

String getData_timegraph(){

	Map<String,Map> resp=[:]

	Long graph_time
	Date then
	then=new Date()

	use (TimeCategory){
		Double val=Double.parseDouble("${graph_timespan}")/1000.0
		then -= (val.toInteger()).seconds
		graph_time=then.getTime()
	}

//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)
			String sa= "${sid}_${attribute}".toString()

			resp[sid]= resp[sid] ?: [:]

			List<Map> data

			data=CgetData(ent,then)
			//return [date: d, (sVAL): sum.round(decimals), t: d.getTime()]
			data=data.collect{ Map it -> [(sDT): (Long)it[sT], (sVAL): getValue(sid, attribute, it[sVAL])]}

			resp[sid][attribute]=data.findAll{ Map it -> (Long)it[sDT] > graph_time}

			//Restrict "bad" values
			if(settings["attribute_${sa}_bad_value"]==true){
				Float min=Float.valueOf(settings["attribute_${sa}_min_value"].toString())
				Float max=Float.valueOf(settings["attribute_${sa}_max_value"].toString())
				resp[sid][attribute]= ((List<Map>)resp[sid][attribute]).findAll{ Map it -> (Double)it[sVAL] > min && (Double)it[sVAL] < max}
			}
		}
	}
	return JsonOutput.toJson(resp)
}

Map getOptions_timegraph(){

	/*Setup Series*/
	//Map<String,Map> series=["series" : [:]]

	Map options=[
			"graphReduction": graph_max_points,
			"graphTimespan": Long.parseLong("${graph_timespan}"),
			"graphUpdateRate": Integer.parseInt(graph_update_rate),
			"graphPointSpan": Long.parseLong(graph_point_span),
			"graphRefreshRate" : Integer.parseInt(graph_refresh_rate),
			"overlays": [ "display_overlays" : show_overlay,
							"horizontal_alignment" : overlay_horizontal_placement,
							"vertical_alignment" : overlay_vertical_placement,
							"order" : overlay_order
			],
			"graphOptions": [
					"tooltip" : ["format" : "short"],
					"width": graph_static_size ? graph_h_size : "100%",
					"height": graph_static_size ? graph_v_size : "100%",
					"chartArea": [ "width":	graph_percent_fill ? "${graph_h_fill}%" : "80%",
									"height": graph_percent_fill ? "${graph_v_fill}%" : "80%"],
					"hAxis": [
							"textStyle": ["fontSize": graph_haxis_font,
										"color": graph_hh_color_transparent ? "transparent" : graph_hh_color
							],
							"gridlines": ["color": graph_ha_color_transparent ? "transparent" : graph_ha_color,
										"count": graph_h_num_grid != "" ? graph_h_num_grid : null
							],
							"format":	graph_h_format==""?"":graph_h_format
					],
					"vAxis": ["textStyle": ["fontSize": graph_vaxis_font,
											"color": graph_vh_color_transparent ? "transparent" : graph_vh_color,
					],
							"gridlines": ["color": graph_va_color_transparent ? "transparent" : graph_va_color],
					],
					"vAxes": [
							0: ["title" : graph_show_left_label ? graph_left_label: null,
								"titleTextStyle": ["color": graph_left_color_transparent ? "transparent" : graph_left_color, "fontSize": graph_left_font],
								"viewWindow": ["min": graph_vaxis_1_min != "" ? graph_vaxis_1_min : null,
											"max": graph_vaxis_1_max != "" ? graph_vaxis_1_max : null],
								"gridlines": ["count" : graph_vaxis_1_num_lines != "" ? graph_vaxis_1_num_lines : null ],
								"minorGridlines": ["count" : 0],
								"format": graph_vaxis_1_format,

							],

							1: ["title": graph_show_right_label ? graph_right_label : null,
								"titleTextStyle": ["color": graph_right_color_transparent ? "transparent" : graph_right_color, "fontSize": graph_right_font],
								"viewWindow": ["min": graph_vaxis_2_min != "" ? graph_vaxis_2_min : null,
											"max": graph_vaxis_2_max != "" ? graph_vaxis_2_max : null],
								"gridlines": ["count" : graph_vaxis_2_num_lines != "" ? graph_vaxis_2_num_lines : null ],
								"minorGridlines": ["count" : 0],
								"format": graph_vaxis_2_format,
							]
					],
					"bar": [ "groupWidth" : graph_bar_width+"%", "fill-opacity" : 0.5],
					"pointSize": graph_scatter_size,
					"legend": !graph_show_legend ? ["position": sNONE] : ["position": graph_legend_position,
																		"alignment": graph_legend_inside_position,
																		"textStyle": ["fontSize": graph_legend_font,
																						"color": graph_legend_color_transparent ? "transparent" : graph_legend_color]],
					"backgroundColor": graph_background_color_transparent ? "transparent" : graph_background_color,
					"curveType": !graph_smoothing ? "" : "function",
					"title": !graph_show_title ? "" : graph_title,
					"titleTextStyle": !graph_show_title ? "" : ["fontSize": graph_title_font, "color": graph_title_color_transparent ? "transparent" : graph_title_color],
					"titlePosition" : graph_title_inside ? "in" : "out",
					"interpolateNulls": true, //for null vals on our chart
					"orientation" : graph_y_orientation == true ? "vertical" : "horizontal",
					"reverseCategories" : graph_x_orientation,
					"series": [:],

			]
	]

	Integer count_
	count_=0

//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)
			String sa= "${sid}_${attribute}".toString()

			String type_
			type_= settings["graph_type_${sa}"] != null ? ((String)settings["graph_type_${sa}"]).toLowerCase() : 'line'
			if(type_ == "stepped") type_="steppedArea"
			Integer axes_=settings["graph_axis_number_${sa}"] == "Left" ? 0 : 1
			String stroke_color=settings["var_${sa}_stroke_color"]
			String stroke_opacity=settings["var_${sa}_stroke_opacity"]
			//def stroke_line_size=settings["var_${sa}_stroke_line_size"]
			//String fill_color=settings["var_${sa}_fill_color"]
			//String fill_opacity=settings["var_${sa}_fill_opacity"]
			def point_size=settings["var_${sa}_point_size"]
			String point_type=settings["var_${sa}_point_type"] != null ? ((String)settings["var_${sa}_point_type"]).toLowerCase() : ""

			type_=type_=='bar' ? 'bars' : type_

			options.graphOptions.series << [(count_.toString()) : [
					"type"			: type_,
					"targetAxisIndex" : axes_,
					"pointSize"	: point_size,
					"pointShape"	: point_type,
					"color"		: stroke_color,
					"opacity"	: stroke_opacity,
				]
			]
			count_++
		}
	}

//	TODO

	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)
			String sa= "${sid}_${attribute}".toString()

	//add colors and thicknesses
			Integer axis=settings["graph_axis_number_${sa}"] == "Left" ? 0 : 1
			String text_color=settings["graph_line_${sa}_color"]
			String text_color_transparent=settings["graph_line_${sa}_color_transparent"]

			Map annotations=[
					"targetAxisIndex": axis,
					"color": text_color_transparent ? "transparent" : text_color
			]

			options.graphOptions.series << annotations
		}
	}

	return options
}

static String getDrawType_timegraph(){
	return "google.visualization.LineChart"
}

static String getRGBA(String hex, opacity){

	String c
	c=hex-"#"
	c=c.toUpperCase()
	Integer i=Integer.parseInt(c, 16)

	Integer r=(i & 0xFF0000) >> 16
	Integer g=(i & 0xFF00) >> 8
	Integer b=(i & 0xFF)
	Float o=opacity/100.0
	String s=sprintf("rgba( %d, %d, %d, %.2f)", r, g, b, o)
	return s
}

String getGraph_timegraph(){
	String fullSizeStyle="margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden"

	String html
	html="""
<html style="${fullSizeStyle}">
	<link rel='icon' href='https://www.shareicon.net/data/256x256/2015/09/07/97252_barometer_512x512.png' type='image/x-icon'/>
	<link rel="apple-touch-icon" href="https://www.shareicon.net/data/256x256/2015/09/07/97252_barometer_512x512.png">
	<head>
${scriptIncludes()}
		<script src="https://cdnjs.cloudflare.com/ajax/libs/svg.js/3.0.16/svg.min.js" integrity="sha256-MCvBrhCuX8GNt0gmv06kZ4jGIi1R2QNaSkadjRzinFs=" crossorigin="anonymous"></script>
		<script type="text/javascript">
google.charts.load('current',{'packages':['corechart']});

let options=[];
let subscriptions={};
let graphData={};

//stack for accumulating points to average
let stack={};

let websocket;

class Loader{
	constructor(){
		this.elem=jQuery(jQuery(document.body).prepend(`
			<div class="loaderContainer">
				<div class="dotsContainer">
					<div class="dot"></div>
					<div class="dot"></div>
					<div class="dot"></div>
				</div>
				<div class="text"></div>
			</div>
		`).children()[0]);
	}

	setText(text){
		this.elem.find('.text').text(text);
	}

	remove(){
		this.elem.remove();
	}
}

function getOptions(){
	return jQuery.get("${state.localEndpointURL}getOptions/?access_token=${state.endpointSecret}", (data) =>{
		options=data;
		console.log("Got Options");
		console.log(options);
	});
}

function getSubscriptions(){
	return jQuery.get("${state.localEndpointURL}getSubscriptions/?access_token=${state.endpointSecret}", (data) =>{
		console.log("Got Subscriptions");
		subscriptions=data;

	});
}

function getGraphData(){
	return jQuery.get("${state.localEndpointURL}getData/?access_token=${state.endpointSecret}", (data) =>{
		console.log("Got Graph Data");
		graphData=data;
	});
}

function parseEvent(event){
	const now=new Date().getTime();
	let odeviceId=event.deviceId;
	let deviceId="d"+odeviceId;

	//only accept relevent events

	if(subscriptions.ids.includes(deviceId) && subscriptions.attributes[deviceId].includes(event.name)){

		let value=isNaN(event.value) ? event.value.replace(/ /g,'') : parseFloat((Math.round(event.value * 100) / 100).toFixed(2));

		let attribute=event.name;

		let state=isNaN(value) ? subscriptions.states[deviceId][attribute][value] : undefined;

		if(state != undefined){
			value=parseFloat(state);
		}

		graphData[deviceId][attribute].push({ date: now, value: value });

		updateOverlay(deviceId, attribute, value);

		if(options.graphRefreshRate === 0) update();
	}
}

async function aupdate(){
	await getGraphData();
	//drawChart();
	update();
}

function update(callback){
	//boot old data
	let min=new Date().getTime();
	min -= options.graphTimespan;

	//First Filter Events that are too old
	Object.entries(graphData).forEach(([deviceId, attributes]) =>{
		Object.entries(attributes).forEach(([attribute, events]) =>{
			graphData[deviceId][attribute]=events.filter(it => it.date > min);
		});
	});
	drawChart(callback);
}

async function onLoad(){
	//append our css
	jQuery(document.head).append(`
		<style>
			.loaderContainer{
				position: fixed;
				z-index: 100;
				width: 100%;
				height: 100%;
				background-color: white;
				display: flex;
				flex-flow: column nowrap;
				justify-content: center;
				align-items: middle;
			}
			.overlay{
				box-sizing: border-box;
				padding: ${overlay_font ? (overlay_font.toInteger()/2): 12}px ${overlay_font}px;
				position: absolute;
				background-color: ${overlay_background_color ? getRGBA(overlay_background_color, overlay_background_opacity) : ""};
				top: 50px;
				left: 100px;
				text-align: center;
				box-shadow: 0 1px 3px rgba(0,0,0,0.12), 0 1px 2px rgba(0,0,0,0.24);
			}
			.overlay-title{
				font-size: ${overlay_font}px;
				text-align: left;
				color: ${overlay_text_color};
				font-family: Arial, Helvetica, sans-serif;

			}
			.overlay-number{
				font-size: ${overlay_font}px;
				font-weight: 900;
				text-align: right;
				padding: 0px 0px 0px ${overlay_font}px;
				color: ${overlay_text_color};
				font-family: Arial, Helvetica, sans-serif;
			}

			.dotsContainer{
				height: 60px;
				padding-bottom: 10px;

				display: flex;
				flex-flow: row nowrap;
				justify-content: center;
				align-items: flex-end;
			}

			@keyframes bounce{
				0%{
					transform: translateY(0);
				}

				50%{
					transform: translateY(-50px);
				}

				100%{
					transform: translateY(0);
				}
			}

			.dot{
				box-sizing: border-box;

				margin: 0 25px;

				width: 10px;
				height: 10px;

				border: solid 5px black;
				border-radius: 5px;

				animation-name: bounce;
				animation-duration: 1s;
				animation-iteration-count: infinite;
			}

			.dot:nth-child(1){
				animation-delay: 0ms;
			}

			.dot:nth-child(2){
				animation-delay: 333ms;
			}

			.dot:nth-child(3){
				animation-delay: 666ms;
			}

			.text{
				font-family: Arial;
				font-weight: 200;
				font-size: 2rem;
				text-align: center;
			}
		</style>
	`);

	let loader=new Loader();

	//first load
	loader.setText('Getting options (1/4)');
	await getOptions();
	loader.setText('Getting device data (2/4)');
	await getSubscriptions();
	loader.setText('Getting events (3/4)');
	await getGraphData();

	loader.setText('Drawing chart (4/4)');

	//create stack
	Object.entries(graphData).forEach(([deviceId, attrs]) =>{
		stack[deviceId]={};
		Object.keys(attrs).forEach(attr =>{
			stack[deviceId][attr]=[];
		});
	})

	update(() =>{
		//destroy loader when we are done with it
		loader.remove();
	});

	if(subscriptions.id=='poll'){
		if(options.graphUpdateRate > 0){
			setInterval(() =>{
				aupdate();
			}, options.graphUpdateRate);
		}
	} else{
		//start our update cycle
		if(options.graphRefreshRate !== -1){
			//start websocket
			websocket=new WebSocket("ws://" + location.hostname + "/eventsocket");
			websocket.onopen=() =>{
				console.log("WebSocket Opened!");
			}
			websocket.onmessage=(event) =>{
				parseEvent(JSON.parse(event.data));
			}

			if(options.graphRefreshRate !== 0){
				setInterval(() =>{
					update();
				}, options.graphRefreshRate);
			}
		}
	}

	//attach resize listener
	window.addEventListener("resize", () =>{
		drawChart();
	});
}

function onBeforeUnload(){
	if(websocket) websocket.close();
}

function averageEvents(minTime, maxTime, data, drop_val){
	const matches=data.filter(it => it.date > minTime && it.date <= maxTime);
	return matches.reduce((sum, it) =>{
		if(sum.value == drop_val) sum.value=0;
		sum.value += it.value / matches.length;
		return sum;
	},{ date: minTime+((maxTime - minTime)/2), value: drop_val});
}

function sumEvents(minTime, maxTime, data, drop_val){
	const matches=data.filter(it => it.date > minTime && it.date <= maxTime);
	return matches.reduce((sum, it) =>{
		if(sum.value == drop_val) sum.value=parseFloat(0);
		sum.value += parseFloat(it.value);
		return sum;
	},{ date: minTime+((maxTime - minTime)/2), value: drop_val});
}


function maxEvents(minTime, maxTime, data, drop_val){
	const matches=data.filter(it => it.date > minTime && it.date <= maxTime);
	if (matches.length != 0){
		return{ date: minTime+((maxTime - minTime)/2), value: Math.max.apply(Math, matches.map(function(o){ return o.value; })) };
	}
	else
		return{ date: minTime+((maxTime - minTime)/2), value: drop_val };
	}

function minEvents(minTime, maxTime, data, drop_val){
	const matches=data.filter(it => it.date > minTime && it.date <= maxTime);
	if(matches.length != 0)
		return{ date: minTime+((maxTime - minTime)/2), value: Math.min.apply(Math, matches.map(function(o){ return o.value; })) };
	else
		return{ date: minTime+((maxTime - minTime)/2), value: drop_val };
}

function midEvents(minTime, maxTime, data, drop_val){
	const matches=data.filter(it => it.date > minTime && it.date <= maxTime);
	if(matches.length != 0)
		return{ date: minTime+((maxTime - minTime)/2), value: matches[Math.floor(matches.length/2)].value };
	else
		return{ date: minTime+((maxTime - minTime)/2), value: drop_val };
}


function getStyle(deviceIndex, attribute){

		let style=subscriptions.var[deviceIndex][attribute]
		let stroke_color=style.stroke_color == null ? "" : style.stroke_color;
		let stroke_opacity=style.stroke_opacity == null ? "" : parseFloat(style.stroke_opacity)/100.0;
		let stroke_width=style.stroke_width == null ? "" : style.stroke_width;
		let fill_color=style.fill_color == null ? "" : style.fill_color;
		let fill_opacity=style.fill_opacity == null ? "" : parseFloat(style.fill_opacity)/100.0;

		let returnString=`{ stroke-color: \${stroke_color}; stroke-opacity: \${stroke_opacity}; stroke-width: \${stroke_width}; fill-opacity: \${fill_opacity}; fill-color: \${fill_color}; }`
		if(subscriptions.graph_type[deviceIndex][attribute] == "Stepped") returnString=`{ stroke-opacity: \${stroke_opacity}; stroke-width: \${stroke_width}; fill-opacity: \${fill_opacity}; fill-color: \${fill_color}; }`

		return returnString;
}

function drawChart(callback){
	let now=new Date().getTime();
	let min=now - options.graphTimespan;

	let dataTable=new google.visualization.DataTable();
	dataTable.addColumn({ label: 'Date', type: 'datetime', });

	let colNums={};
	let i=0;
	subscriptions.ids.forEach((deviceId) =>{

		subscriptions.attributes[deviceId].forEach((attr) =>{
			console.log(deviceId+" "+attr);
			dataTable.addColumn({ label: subscriptions.labels[deviceId][attr].replace('%deviceName%', subscriptions.sensors[deviceId].displayName).replace('%attributeName%', attr), type: 'number' });
			dataTable.addColumn({ role: "style" });
		});
	});

	// BUILD THE STYLES

	// COLLATE THE CURRENT DATA

	let accumData={};
	let then=now - options.graphTimespan;
	let spacing=options.graphPointSpan;
	let overlay=10;
	var current;
	var drop_val;
	var newEntry;
	var next;

	//adjust for days
	if(options.graphPointSpan >= 86400000){
		let d=new Date(then);
		d.setHours(0, 0, 0, 0);
		then=d.getTime();
	}

	console.info(subscriptions);

	//map the graph data
	Object.entries(graphData).forEach(([deviceIndex, attributes]) =>{
		Object.entries(attributes).forEach(([attribute, events]) =>{

			let func=subscriptions.var[deviceIndex][attribute].function;
			let num_events=events.length;

			extend_left=subscriptions.extend[deviceIndex][attribute].left;
			extend_right=subscriptions.extend[deviceIndex][attribute].right;
			let drop_line=subscriptions.drop[deviceIndex][attribute].valid;
			let drop_val=null;

			if(drop_line == "true"){
				drop_val=parseFloat(subscriptions.drop[deviceIndex][attribute].value);
			} else if (num_events>0 && extend_left){
				drop_val=events[0].value;
			}

			current=then;

			while (current < now){
				//if (subscriptions.states[deviceIndex][attribute] != undefined && events.length > 0){
				//	if(drop_val == null){
				//		drop_val=events[0].value;
				//	} else{
				//		drop_val=newEntry.value;
				//	}
				//}
				if(subscriptions.graph_type[deviceIndex][attribute] == "Stepped"){
					drop_val=newEntry == undefined ? events[0].value : newEntry.value;
				}
				next=current+spacing;

				switch (func){
					case "Average": newEntry=averageEvents(current, next, events, drop_val); break;
					case "Min":	newEntry=minEvents(current, next, events, drop_val);	break;
					case "Max":	newEntry=maxEvents(current, next, events, drop_val);	break;
					case "Mid":	newEntry=midEvents(current, next, events, drop_val);	break;
					case "Sum":	newEntry=sumEvents(current, next, events, drop_val);	break;
				}

				if(drop_line != "true"){
					if(num_events > 0 && next >= events[0].date && extend_left){
						drop_val=null;
					}
					if(num_events > 0 && events[num_events-1].date <= next && extend_right){
						drop_val=events[num_events-1].value;
					}
				}

				accumData[newEntry.date]=[ ...(accumData[newEntry.date] ? accumData[newEntry.date] : []), newEntry.value];
				accumData[newEntry.date]=[ ...(accumData[newEntry.date] ? accumData[newEntry.date] : []), getStyle(deviceIndex, attribute)];
				current += spacing;

			}
		});
	});

	let parsedGraphData=Object.entries(accumData).map(([date, vals]) => [moment(parseInt(date)).toDate(), ...vals]);

	parsedGraphData.forEach(it =>{
		dataTable.addRow(it);
	});

	// DRAW THE GRAPH

	let graphOptions=Object.assign({}, options.graphOptions);

	graphOptions.hAxis=Object.assign(graphOptions.hAxis,{ viewWindow:{ min: moment(min).toDate(), max: moment(now).toDate() } });

	let chart=new ${drawType_timegraph}(document.getElementById("timeline"));

	//if we have a callback
	if(callback) google.visualization.events.addListener(chart, 'ready', callback);

	if(options.overlays.display_overlays) google.visualization.events.addListener(chart, 'ready', placeMarker.bind(chart, dataTable));

	chart.draw(dataTable, graphOptions);

}

function updateOverlay(deviceId, attribute, value){
	console.log(deviceId+" "+attribute+" "+value);
	let searchString="#overlay-"+deviceId+"_"+attribute+"-number";
	let val=parseFloat(value).toFixed(1)+" "+subscriptions.var[deviceId][attribute].units;
	console.log(searchString);
	jQuery(searchString).text(val);
}

function placeMarker(dataTable){
		var cli=this.getChartLayoutInterface();
		var chartArea=cli.getChartAreaBoundingBox();
		let width=jQuery('#graph-overlay').outerWidth();
		let height=jQuery('#graph-overlay').outerHeight();
		let overlay=options.overlays;

		console.debug("Width =", width);
		console.debug(chartArea);
		console.debug(cli);

		switch (overlay.vertical_alignment){
			case "Top":	document.querySelector('.overlay').style.top=Math.floor(chartArea.top) + "px"; + "px"; break;
			case "Middle": document.querySelector('.overlay').style.top=Math.floor(chartArea.height/2+chartArea.top-height/2) + "px"; + "px"; break;
			case "Bottom":	document.querySelector('.overlay').style.top=Math.floor(chartArea.height+chartArea.top-height) + "px"; + "px"; break;

		}
		switch (overlay.horizontal_alignment){
			case "Left":	document.querySelector('.overlay').style.left=Math.floor(chartArea.left) + "px"; break;
			case "Middle": document.querySelector('.overlay').style.left=Math.floor(chartArea.width/2-(width/2)+chartArea.left) + "px"; break;
			case "Right":	document.querySelector('.overlay').style.left=Math.floor(chartArea.width+chartArea.left-width) + "px"; break;

		}

		//document.querySelector('.overlay').style.width=Math.floor(chartArea.width*0.25) + "px";
		//document.querySelector('.overlay').style.height=Math.floor(chartArea.height*0.25) + "px";
	};

		google.charts.setOnLoadCallback(onLoad);
		window.onBeforeUnload=onBeforeUnload;

	</script>
	<meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
	</head>
	<body style="${fullSizeStyle}">
	<div id="timeline" style="${fullSizeStyle}" align="center"></div>
	"""
	if(show_overlay==true) html+= getOverlay_timegraph()

	html+= """

	</body>

</html>
	"""

	return html
}



String getOverlay_timegraph(){

	String html
	html="""<div id="graph-overlay" class="overlay"><table style="width:100%">"""

	List<String> val=new JsonSlurper().parseText(overlay_order) as List<String>
	for(String str in val){
		String[] splitStr=str.split('_')
		String sid=splitStr[1]
		String attribute=splitStr[2]
		String sa= "${sid}_${attribute}".toString()

		Map ent=findDataSourceEntry(sid,attribute)
		Double v=getValue(sid,attribute,getLatestVal(ent))

		String units=settings["units_${sa}"] ? settings["units_${sa}"] : ""
		String name; name=settings["graph_name_override_${sa}"]
		name=name.replaceAll("%deviceName%", sMs(ent,sDISPNM)).replaceAll("%attributeName%", attribute)
		String s=sprintf("%.1f%s",v,units)
		html += """<tr><td class="overlay-title" id="overlay-${sa}-name">${name}</td>
						<td class="overlay-number" id="overlay-${sa}-number">${s}</td></tr>"""
	}
	html += """</div>"""

	return html
}

//oauth endpoints

Map getSubscriptions_timegraph(){
	List<String> ids=[]
	Map sensors_=[:]
	Map attributes=[:]
	Map labels=[:]
	Map drop_=[:]
	Map extend_=[:]
	Map var_=[:]
	Map graph_type_=[:]
	Map states_=[:]

	Boolean isPoll
	isPoll=(Boolean)state.hasFuel

//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)
			String sa= "${sid}_${attribute}".toString()
			String dn=sMs(ent,sDISPNM)
			//String typ=sMs(ent,sT).capitalize()

			if(!ids.contains(sid)) ids << sid
			//TODO

		//only take what we need
			//Map sensors_fmt=gtSensorFmt()
			sensors_[sid]=[ (sID): sid /*, idAsLong: sensor.idAsLong */, (sDISPNM): dn ]

			attributes[sid]= attributes[sid] ?: []
			attributes[sid] << attribute

			String attr=attribute

			labels[sid]= labels[sid] ?: [:]

			labels[sid][attr]=settings["graph_name_override_${sa}"]

			states_[sid]= states_[sid] ?: [:]

			String varn= "attribute_${sa}_states".toString()
			if((List)settings[varn] && settings["attribute_${sa}_custom_states"] == true){
				states_[sid][attr]=[:]
				for(String st in (List<String>)settings[varn]){
					states_[sid][attr][st]=settings["attribute_${sa}_${st}"]
				}
			}

			drop_[sid]= drop_[sid] ?: [:]

			Boolean drop_valid
			drop_valid=false
			if(settings["attribute_${sa}_drop_line"] == true)
				drop_valid=true

			drop_[sid][attr]=[	valid: drop_valid ? "true" : "false",
								(sVAL): drop_valid ? settings["attribute_${sa}_drop_value"] : "null"
			]

			extend_[sid]= extend_[sid] ?: [:]
			extend_[sid][attr]=[
					right: settings["attribute_${sa}_extend_right"],
					left: settings["attribute_${sa}_extend_left"]
			]

			graph_type_[sid]= graph_type_[sid] ?: [:]
			graph_type_[sid][attr]=settings["graph_type_${sa}"]

			def stroke_color=settings["var_${sa}_stroke_color"]
			def stroke_opacity=settings["var_${sa}_stroke_opacity"]
			def stroke_line_size=settings["var_${sa}_stroke_line_size"]
			def fill_color=settings["var_${sa}_fill_color"]
			def fill_opacity=settings["var_${sa}_fill_opacity"]
			def function=settings["var_${sa}_function"]

			var_[sid]= var_[sid] ?: [:]
			var_[sid][attr]=[
					stroke_color : stroke_color,
					stroke_opacity : stroke_opacity,
					stroke_width:	stroke_line_size,
					fill_color:	fill_color,
					fill_opacity:	fill_opacity,
					function:	function,
					units:		settings["units_${sa}"] ?: sBLK,
			]
		}
	}

	Map subscriptions=[
		(sID): isPoll ? 'poll' : 'sensor',
		ids: ids, //.sort(),
		sensors: sensors_,
		attributes: attributes,
		labels : labels,
		drop : drop_,
		extend: extend_,
		graph_type: graph_type_,
		var : var_,
		states: states_
	]

	return subscriptions
}









/*
 * TODO: Heatmap methods
 */


def mainHeatmap(){
	mainShare1("""Choose Numeric Attributes or common sensor attributes (like on/off, open/close, present/not present,
			detected/clear, active/inactive, wet/dry, last Activity)""",
			'graph_update_rate')
}

def deviceHeatmap(){
	deviceShare1(true,false,true)
}

def attributeHeatmap(){
	attributeShare1(true)
}

static String dd(Double num){
	if(num<10.0D) return s0+num.toInteger().toString()
	else return num.toInteger().toString()
}

static String convertToString(Long msec_){
	Long msec=msec_
	if(msec == 0L) return "00:00:00"

	Double hours=Math.floor(msec/3600000.0D)
	Double mins=Math.floor((msec%3600000.0D)/60000.0D)
	Double secs=Math.floor((msec%60000.0D)/1000.0D)

	return dd(hours)+":"+dd(mins)+":"+dd(secs)
}

def graphHeatmap(){

	List<Map> decayEnum=[["1000":"1 Second"],	["30000":"30 Seconds"], ["60000":"1 Minute"], ["300000":"5 Minutes"], ["600000":"10 Minutes"],
						["1800000":"Half Hour"], ["3600000":"1 Hour"], ["7200000":"2 Hours"], ["21600000":"6 Hours"], ["43200000":"12 Hours"], ["86400000":"1 Day"],
						["172800000":"2 Days"], ["259200000":"3 Days"], ["345600000":"4 Days"], ["432000000":"5 Days"], ["518400000":"6 Days"], ["604800000":"7 Days"]]

	List<Map> typeEnum=[[(sVAL): "Value"], ["time" : "Trigger (Time Since Last Update)"]]

//	TODO
	Integer count_
	count_=0

	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){

	//Get Device Count
			count_++
		}
	}
	app.updateSetting ("attribute_count", count_)

	dynamicPage(name: "graphSetupPage"){
		List container
		hubiForm_section("General Options", 1, sBLK, sBLK){

			container=[]
			input( (sTYPE): sENUM, (sNM): "graph_type",(sTIT): "<b>Select Graph Type</b>", multiple: false, required: false, options: typeEnum, defaultValue: sVAL, submitOnChange: true)
			inputGraphUpdateRate()

			if(!graph_type) graph_type=sVAL
			if(graph_type == "time"){
				input( (sTYPE): sENUM, (sNM): "graph_decay",(sTIT): "<b>Decay Rate</b>", multiple: false, required: false, options: decayEnum, defaultValue: "300000", submitOnChange: true)
			}

			container << hubiForm_color ("Graph Background", "graph_background", "#FFFFFF", false)
			container << hubiForm_color ("Graph Line", "graph_line", "#000000", false)
			container << hubiForm_line_size ((sTIT): "Graph Line",
					(sNM): "graph",
					default: 2,
					min: 1,
					max: count_,
			)

			hubiForm_container(container, 1)
		}

		Integer num_
		if(graph_num_gradients == null){
			settings["graph_num_gradients"]="2"
			app.updateSetting ("graph_num_gradients", "2")
			num_=2
		}else{
			num_=graph_num_gradients.toInteger()
		}
		hubiForm_section("Level Gradient", 1, sBLK, sBLK){

			container=[]

			container << hubiForm_text_input("Number of Gradient Levels",
					"graph_num_gradients",
					"2",
					true)

			List subcontainer
			if(graph_type == sVAL){
				Integer gradient
				for(gradient=0; gradient < num_; gradient++){
					subcontainer=[]
					String titleString
					if(gradient == 0) titleString="Start"
					else if(gradient == num_-1) titleString="End"
					else titleString="Mid"

					subcontainer << hubiForm_text_input(titleString+" Value",
							"graph_gradient_${gradient}_value",
							(gradient*10).toString(),
							false)

					subcontainer << hubiForm_color	("Gradient #"+gradient,
							"graph_gradient_${gradient}",
							hubiTools_rotating_colors(gradient),
							false)
					container << hubiForm_subcontainer([objects: subcontainer, breakdown: [0.25, 0.75]])

				}
			}else{
				Long add_time=(graph_decay.toInteger()/(graph_num_gradients.toInteger()-1))
				Long curr_time
				curr_time=0L
				Integer gradient
				for(gradient=0; gradient < num_; gradient++){
					subcontainer=[]

					subcontainer << hubiForm_text_format(
							[text: convertToString(curr_time),
							horizontal_align: "right",
							vertical_align: "20px",
							sz: 24] )

					app.updateSetting ("graph_gradient_${gradient}_value", curr_time)

					subcontainer << hubiForm_color	("Gradient #"+gradient,
							"graph_gradient_${gradient}",
							hubiTools_rotating_colors(gradient),
							false)

					container << hubiForm_subcontainer([objects: subcontainer, breakdown: [0.25, 0.75]])

					curr_time += add_time

				}
			}
			hubiForm_container(container, 1)
		}

		hubiForm_section("Graph Size", 1, sBLK, sBLK){
			container=[]
			Integer default_=Math.ceil(Math.sqrt(count_)).intValue()
			Integer cols=graph_num_columns ? "${graph_num_columns}".toInteger() : default_
			Integer rows=Math.ceil(count_/cols).intValue()
			container << hubiForm_slider ((sTIT): "Number of Columns<br><small>"+count_+" Devices/Attributes -- "+cols+" X "+rows+"</small>",
					(sNM): "graph_num_columns",
					default: default_,
					min: 1,
					max: count_,
					units: " columns",
					submit_on_change: true)

			input( (sTYPE): sBOOL, (sNM): "graph_static_size",(sTIT): "<b>Set size of Graph?</b><br><small>(False=Fill Window)</small>", defaultValue: false, submitOnChange: true)
			if((Boolean)settings.graph_static_size){
				container << hubiForm_slider ((sTIT): "Horizontal dimension of the graph", (sNM): "graph_h_size", default: 800, min: 100, max: 3000, units: " pixels")
				container << hubiForm_slider ((sTIT): "Vertical dimension of the graph", (sNM): "graph_v_size", default: 600, min: 100, max: 3000, units: " pixels")
			}

			hubiForm_container(container, 1)
		}
		hubiForm_section("Annotations", 1, sBLK, sBLK){
			container=[]
			container << hubiForm_switch([(sTIT): "Show values inside Heat Map?", (sNM): "show_annotations", default: false, submit_on_change: true])
			if((Boolean)settings.show_annotations){
				container << hubiForm_font_size	((sTIT): "Annotation", (sNM): "annotation", default: 16, min: 2, max: 40)
				container << hubiForm_color		("Annotation", "annotation", "#FFFFFF", false)
				container << hubiForm_color		("Annotation Aura", "annotation_aura", "#000000", false)
				container << hubiForm_slider	((sTIT): "Number Decimal Places", (sNM): "graph_decimals", default: 1, min: 0, max: 4, units: " decimal places")
				container << hubiForm_switch	([(sTIT): "Bold Annotation", (sNM): "annotation_bold", default:false])
				container << hubiForm_switch	([(sTIT): "Italic Annotation", (sNM): "annotation_italic", default:false])
			}
			hubiForm_container(container, 1)
		}
	}
}

String getData_heatmap(){
	Map<String,Map> resp=[:]
	Date now=new Date()
	//def then=new Date(0)

//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
//			String rid=ent.rid.toString()
			String attribute=sMs(ent,sA)

			resp[sid]= resp[sid] ?: [:]

			Map lst= gtLastData(ent)
			// [date: date, (sVAL): v, t: t]
			if(lst && sMs(ent,'aa') == 'lastupdate'){
				//Date lastEvent=(Date)lst.date //sensor.getLastActivity()
				Long latest= (Long)lst[sT] //lastEvent ? lastEvent.getTime() : 0L
				resp[sid]['lastupdate']=[current: (now.getTime()-latest), (sDT): latest]
			}else{
				def latest=lst ? lst[sVAL] : 0 // sensor.latestState(attribute)
				resp[sid][attribute]=[current: latest, (sDT): lst[sDT] ?: now]
			}
		}
	}
	return JsonOutput.toJson(resp)
}

Map getOptions_heatmap(){

	List colors=[]

//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)

			String attrib_string="attribute_${sid}_${attribute}_color"
			String transparent_attrib_string="attribute_${sid}_${attribute}_color_transparent"
			colors << (settings[transparent_attrib_string] ? "transparent" : settings[attrib_string])
		}
	}
/*
	String axis1,axis2
	if(graph_type == "1"){
		axis1="hAxis"
		axis2="vAxis"
	}else{
		axis1="vAxis"
		axis2="hAxis"
	} */

	Map options=[
			"graphUpdateRate": Integer.parseInt(graph_update_rate),
			(sGRAPHT): sMs(settings,'graph_type'),
			"graphOptions": [
					"bar" : [ "groupWidth" : "100%" ],
					"width": graph_static_size ? graph_h_size : "100%",
					"height": graph_static_size ? graph_v_size: "100%",
					"timeline": [
							"rowLabelStyle": ["fontSize": graph_axis_font, "color": graph_axis_color_transparent ? "transparent" : graph_axis_color],
							"barLabelStyle": ["fontSize": graph_axis_font]
					],
					"backgroundColor": graph_background_color_transparent ? "transparent" : graph_background_color,
					"isStacked": true,
					"chartArea": [ "left": 10,
								"right" : 10,
								"top": 10,
								"bottom": 10
					],
					"legend" : [ "position" : sNONE ],
					"hAxis": [ "textPosition": sNONE,
							"gridlines" : [ "count" : s0 ]
					],

					"vAxis": [ "textPosition": sNONE,
							"gridlines" : [ "count" : s0 ]
					],
					"annotations" : [	"alwaysOutside": "false",
										"textStyle": [
												"fontSize": annotation_font,
												"bold":	annotation_bold,
												"italic": annotation_italic,
												"color":	annotation_color_transparent ? "transparent" : annotation_color,
												"auraColor":annotation_aura_color_transparent ? "transparent" : annotation_aura_color,
										],
										"stem": [ "color": "transparent",
												"highContrast": "false"
										],
					],
			]
	]
	return options
}


String getGraph_heatmap(){
	String fullSizeStyle="margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden"

	String html="""
<html style="${fullSizeStyle}">
	<head>
${scriptIncludes1()}
		<script type="text/javascript">
google.charts.load('current',{'packages':['corechart']});

let options=[];
let subscriptions={};
let graphData={};

//stack for accumulating points to average
let stack={};

let websocket;

class Loader{
	constructor(){
		this.elem=jQuery(jQuery(document.body).prepend(`
			<div class="loaderContainer">
				<div class="dotsContainer">
					<div class="dot"></div>
					<div class="dot"></div>
					<div class="dot"></div>
				</div>
				<div class="text"></div>
			</div>
		`).children()[0]);
	}

	setText(text){
		this.elem.find('.text').text(text);
	}

	remove(){
		this.elem.remove();
	}
}

function getOptions(){
	return jQuery.get("${state.localEndpointURL}getOptions/?access_token=${state.endpointSecret}", (data) =>{
		options=data;
		console.log("Got Options");
		console.log(options);
	});
}

function getSubscriptions(){
	return jQuery.get("${state.localEndpointURL}getSubscriptions/?access_token=${state.endpointSecret}", (data) =>{
		console.log("Got Subscriptions");
		subscriptions=data;

	});
}

function getValue(data, date, attr){

	if(options.graphType == "time" || attr == "lastupdate"){
		let now=new Date();
		let then=new Date(date);
		return now.getTime()-then.getTime();
	}

	switch (data){
		case "active"	: return 100;
		case "inactive"	: return 0;
		case "on"		: return 100;
		case "off"		: return 0;
		case "open"		: return 100;
		case "closed"	: return 0;
		case "detected"	: return 100;
		case "not detected" : return 0;
		case "clear"		: return 0;
		case "wet"		: return 100;
		case "dry"		: return 0;
		case "unlocked"		: return 100;
		case "locked"	: return 0;
		case "present"		: return 100;
		case "not present"	: return 0;
		case "sleeping"		: return 100;
		case "not sleeping"	: return 0;
		case "muted"		: return 100;
		case "unmuted"	: return 0;
	}
	return data;
}

function getGraphData(){
	return jQuery.get("${state.localEndpointURL}getData/?access_token=${state.endpointSecret}", (data) =>{
		graphData=data;
	});
}

function parseEvent(event){
	let odeviceId=event.deviceId;
	let deviceId="d"+odeviceId;

	//only accept relevent events
	if((subscriptions.ids.includes(deviceId) && subscriptions.attributes[deviceId].includes(event.name)) ||
		(subscriptions.ids.includes(deviceId) && subscriptions.attributes[deviceId].includes("lastupdate"))){
		let value=event.value;
		let attribute=event.name;

		console.log("Trigger: ", attribute, "Value: ", value);

		if(subscriptions.attributes[deviceId].includes("lastupdate")){
			let now=new Date();
			graphData[deviceId]["lastupdate"].current=now.getTime();
			graphData[deviceId]["lastupdate"].date=new Date();
		} else{
			graphData[deviceId][attribute].current=value;
			graphData[deviceId][attribute].date=new Date();
		}

		//update if we are realtime
		if(options.graphUpdateRate === 0) update();
	}
}

async function aupdate(){
	await getGraphData();
	drawChart();
}

function update(callback){
	drawChart(callback);
}

async function onLoad(){
	//append our css
	jQuery(document.head).append(`
		<style>
			.loaderContainer{
				position: fixed;
				z-index: 100;

				width: 100%;
				height: 100%;

				background-color: white;

				display: flex;
				flex-flow: column nowrap;
				justify-content: center;
				align-items: middle;
			}

			.dotsContainer{
				height: 60px;
				padding-bottom: 10px;

				display: flex;
				flex-flow: row nowrap;
				justify-content: center;
				align-items: flex-end;
			}

			@keyframes bounce{
				0%{
					transform: translateY(0);
				}

				50%{
					transform: translateY(-50px);
				}

				100%{
					transform: translateY(0);
				}
			}

			.dot{
				box-sizing: border-box;

				margin: 0 25px;

				width: 10px;
				height: 10px;

				border: solid 5px black;
				border-radius: 5px;

				animation-name: bounce;
				animation-duration: 1s;
				animation-iteration-count: infinite;
			}

			.dot:nth-child(1){
				animation-delay: 0ms;
			}

			.dot:nth-child(2){
				animation-delay: 333ms;
			}

			.dot:nth-child(3){
				animation-delay: 666ms;
			}

			.text{
				font-family: Arial;
				font-weight: 200;
				font-size: 2rem;
				text-align: center;
			}
		</style>
	`);

	let loader=new Loader();

	//first load
	loader.setText('Getting options (1/4)');
	await getOptions();
	loader.setText('Getting device data (2/4)');
	await getSubscriptions();
	loader.setText('Getting events (3/4)');
	await getGraphData();
	loader.setText('Drawing chart (4/4)');

	update(() =>{
		//destroy loader when we are done with it
		loader.remove();
	});

	if(subscriptions.id=='poll'){
		if(options.graphUpdateRate > 0){
			setInterval(() =>{
				aupdate();
			}, options.graphUpdateRate);
		}
	} else{
		//start our update cycle
		if(options.graphUpdateRate !== -1){
			//start websocket
			websocket=new WebSocket("ws://" + location.hostname + "/eventsocket");
			websocket.onopen=() =>{
				console.log("WebSocket Opened!");
			}
			websocket.onmessage=(event) =>{
				parseEvent(JSON.parse(event.data));
			}

			if(options.graphUpdateRate !== 0){
				setInterval(() =>{
					update();
				}, options.graphUpdateRate);
			}
		}
	}

	//attach resize listener
	window.addEventListener("resize", () =>{
		drawChart();
	});
}

function onBeforeUnload(){
	if(websocket) websocket.close();
}

function dd(num){
	if(num<10) return "0"+num.toString();
	else return num.toString();
}

function convertToString(msec){

	if(msec == "0" || msec == 0) return "0 Seconds ago";

	let days=parseInt(Math.floor(msec/86400000));
	let hours=parseInt(Math.floor((msec%86400000)/3600000));
	let mins=parseInt(Math.floor((msec%3600000)/60000));
	let secs=parseInt(Math.floor((msec%60000)/1000));

	let dayString=days == 0 ? "" : days.toString()+" Days";
		dayString=days == 1 ? "1 Day" : dayString
	let hourString=hours == 0 ? "" : hours.toString()+" Hours ";
		hourString=hours == 1 ? "1 Hour" : hourString;
	let minuteString=mins == 0 ? "" : mins.toString()+" Minutes ";
		minuteString=mins == 1 ? "1 Minute" : minuteString;
	let secondString=secs == 0 ? "" : secs.toString()+" Seconds ";
		secondString=secs == 1 ? "1 Second" : secondString;

	return dayString+" "+hourString+" "+minuteString+" "+secondString;
}


function getDataList(){
	const date_options={
		weekday: "long",
		year: "numeric",
		month:"long",
		day:"numeric"
	};
	const time_options ={
		hour12 : true,
		hour: "2-digit",
		minute: "2-digit",
		second: "2-digit"
	};

	let data=[];

	subscriptions.order.forEach(orderStr =>{
		const splitStr=orderStr.split('_');
		const deviceId=splitStr[1];
		const attr=splitStr[2];
		const event=graphData[deviceId][attr];

		const cur_=parseFloat(getValue(event.current, event.date, attr));
		var cur_String='';
		var units_=``;

		var t_date=new Date(event.date);
		var date_String=t_date.toLocaleDateString("en-US",date_options);
		var time_String=t_date.toLocaleTimeString("en-US",time_options);

		const name=subscriptions.labels[deviceId][attr].replace('%deviceName%', subscriptions.sensors[deviceId].displayName).replace('%attributeName%', attr);

		var value_=event.current;
		var stats_=`\${name}\nCurrent: \${value_}\${units_}\nDate: \${date_String} \${time_String}`;

		if(attr == "lastupdate"){
				value_=convertToString(value_);
				stats_=`\${name} \nLast Update: \${value_}\${units_}\nDate: \${date_String} \${time_String}`;
		}

		data.push({name: name, value: cur_, str: stats_});
	});

	return data;
}

function drawChart(callback){

	//get number of elements

	let numElements=subscriptions.count;

	let colorProfile=[];
	for (i=0; i<subscriptions.num_gradients; i++)
		colorProfile.push(subscriptions.gradients[i]);

	let dataArray=[];
	let tempArray=[];
	let dim=getRowColumnsBlank(numElements);
	let map=new Map();
	let cols=subscriptions.num_columns;
	let rows=Math.ceil(numElements/cols);

	//Build the header based on the number of elements
	let header=[];
	header.push('Device');
	for (i=0; i< cols; i++){
		header.push("R"+i);
		header.push({role:"style"});
		header.push({role:"tooltip"});
		header.push({role:"annotation"});
	}

	dataArray.push(header);

	let data=getDataList();

	let idx=0;
	let color=0;
	let width=subscriptions.line_thickness;
	let line_color=subscriptions.line_color;
	let fill_opacity=1.0;
	for (i=0; i<rows; i++){
		tempArray=[];
		tempArray.push("Row"+i);
		for (j=0; j<cols; j++){

			if(idx>= numElements){
				tempArray.push(0);
				value='';
				str='';
				color=options.graphOptions.backgroundColor;
				line_color=subscriptions.line_color;
				opacity=0.0;
				width=0;
				fill_opacity=0.0;
				attr='';
			} else{
				tempArray.push(10);
				value=data[idx].value;
				str=data[idx].str;
				color=getcolor(colorProfile, value);
				line_color=subscriptions.line_color;
				opacity=1.0;
				width=subscriptions.line_thickness;
				if(subscriptions.show_annotations){
					val=parseFloat(value).toFixed(subscriptions.decimals);
					attr=val;
				} else{
					attr='';
				}
			}

			tempArray.push('stroke-color: '+line_color+'; stroke-opacity: '+opacity+'; stroke-width: '+width+'; color: '+color+'; fill-opacity: '+fill_opacity );
			tempArray.push(str);
			tempArray.push(attr);
			idx++;
		}
		dataArray.push(tempArray);
	}
	var dataTable=google.visualization.arrayToDataTable(dataArray);

	chart=new google.visualization.BarChart(document.getElementById("timeline"));

	if(callback) google.visualization.events.addListener(chart, 'ready', callback);

	chart.draw(dataTable, options.graphOptions);
}

google.charts.setOnLoadCallback(onLoad);
window.onBeforeUnload=onBeforeUnload;
		</script>
	</head>
	<body style="${fullSizeStyle}">
		<div id="timeline" style="${fullSizeStyle}" align="center"></div>
	</body>
	</html>
	"""

	return html
}


//oauth endpoints

Map getSubscriptions_heatmap(){

	Integer count_
	count_=0
	List _ids=[]
	Map _attributes=[:]
	Map labels=[:]
	Map gradients=[:]

	Boolean isPoll
	isPoll=(Boolean)state.hasFuel

//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){
			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)
			String nattribute
			nattribute=attribute
			if(sMs(ent,'aa') == "lastupdate") nattribute=ent.aa

			_ids << sid
			_attributes[sid] = _attributes[sid] ?: []
			_attributes[sid] << nattribute

			count_++

			labels[sid]= labels[sid] ?: [:]
			labels[sid][nattribute]="${sid} ${nattribute}"

			labels[sid][nattribute]=settings["graph_name_override_${sid}_${attribute}"]
		}
	}

	Map sensors_fmt=gtSensorFmt()

	Integer i
	Integer e=graph_num_gradients.toInteger()
	for(i=0; i<e; i++){
		gradients[i]=["val": settings["graph_gradient_${i}_value"], "color": settings["graph_gradient_${i}_color"]]
	}

	List order=graph_order ? parseJson(graph_order) : []

	Map subscriptions=[
			(sID): isPoll ? 'poll' : 'sensor',
			"decimals" : graph_decimals,
			"count" : count_,
			"sensors": sensors_fmt,
			"ids": _ids,
			"attributes": _attributes,
			"labels": labels,
			"order": order,
			"show_annotations": show_annotations,
			"gradients": gradients,
			"num_gradients" : graph_num_gradients.toInteger(),
			"num_columns" : graph_num_columns,
			"line_color" : graph_line_color,
			"line_thickness" : graph_line_size,
	]

	return subscriptions
}







/*
 * TODO: Linegraph methods
 */


def mainLinegraph(){
	mainShare1(sNL,'graph_timespan')
}
def attributeLinegraph(){
	attributeShare1()
}

def deviceLinegraph(){
	deviceShare1()
}

def graphLinegraph(){

	List<Map> timespanEnum2=[
			["60000":"1 Minute"], ["120000":"2 Minutes"], ["300000":"5 Minutes"], ["600000":"10 Minutes"],
			["2400000":"30 minutes"], ["3600000":"1 Hour"], ["43200000":"12 Hours"],
			["86400000":"1 Day"], ["259200000":"3 Days"], ["604800000":"1 Week"]
	]

	dynamicPage(name: "graphSetupPage"){

		Boolean non_numeric
		non_numeric=false
		List container
//	TODO
		List<Map> dataSources=gtDataSources()
		if(dataSources){
			for(Map ent in dataSources){

				String attribute=sMs(ent,sA)

				Map a=gtStartEndTypes(ent,attribute)
				if(a)
					non_numeric = true
			}
		}

		if(non_numeric){
			app.updateSetting ("graph_max_points", 0)
		}

		hubiForm_section("General Options", 1, sBLK, sBLK){
			input( (sTYPE): sENUM, (sNM): "graph_type",(sTIT): "<b>Graph Type</b>", defaultValue: "Line Graph", options: ["Line Graph", "Area Graph", "Scatter Plot"], submitOnChange: true)
			inputGraphUpdateRate()
			input( (sTYPE): sENUM, (sNM): "graph_timespan",(sTIT): "<b>Select Time span to Graph</b>", multiple: false, required: true, options: timespanEnum, defaultValue: "43200000")
			container=[]
			container << hubiForm_color ("Graph Background",	"graph_background", "#FFFFFF", false)
			container << hubiForm_switch((sTIT): "Smooth Graph Points", (sNM): "graph_smoothing", default: false)
			container << hubiForm_switch((sTIT): "<b>Flip Graph to Vertical?</b><br><small>(Rotate 90 degrees)</small>", (sNM): "graph_y_orientation", default: false)
			container << hubiForm_switch((sTIT): "<b>Reverse Data Order?</b><br><small> (Flip data left to Right)</small>", (sNM): "graph_z_orientation", default: false)
			if(!non_numeric)
				container << hubiForm_slider ((sTIT): "Maximum number of Data Points?</b><br><small>(Zero for ALL)</small>", (sNM): "graph_max_points", default: 0, min: 0, max: 1000, units: " data points", submit_on_change: false)

			hubiForm_container(container, 1)

		}

		hubiForm_section("Graph Title", 1, sBLK, sBLK){
			container=[]
			container << hubiForm_switch((sTIT): "Show Title on Graph", (sNM): "graph_show_title", default: false, submit_on_change: true)
			if(graph_show_title==true){
				container << hubiForm_text_input ("Graph Title", "graph_title", "Graph Title", false)
				container << hubiForm_font_size ((sTIT): "Title", (sNM): "graph_title", default: 9, min: 2, max: 20)
				container << hubiForm_color	("Title", "graph_title", "#000000", false)
				container << hubiForm_switch	((sTIT): "Graph Title Inside Graph?", (sNM): "graph_title_inside", default: false)
			}
			hubiForm_container(container, 1)
		}

		hubiForm_section("Graph Size", 1, sBLK, sBLK){
			container=[]
			container << hubiForm_switch	((sTIT): "<b>Set size of Graph?</b><br><small>(False=Fill Window)</small>", (sNM): "graph_static_size", default: false, submit_on_change: true)
			if(graph_static_size==true){
				container << hubiForm_slider ((sTIT): "Horizontal dimension of the graph", (sNM): "graph_h_size", default: 800, min: 100, max: 3000, units: " pixels", submit_on_change: false)
				container << hubiForm_slider ((sTIT): "Vertical dimension of the graph", (sNM): "graph_v_size", default: 600, min: 100, max: 3000, units: " pixels", submit_on_change: false)
			}

			hubiForm_container(container, 1)
		}

		hubiForm_section("Horizontal Axis", 1, sBLK, sBLK){
			//Axis
			container=[]
			container << hubiForm_font_size	((sTIT): "Horizontal Axis", (sNM): "graph_haxis", default: 9, min: 2, max: 20)
			container << hubiForm_color	("Horizontal Header", "graph_hh", "#C0C0C0", false)
			container << hubiForm_color	("Horizontal Axis", "graph_ha", "#C0C0C0", false)
			container << hubiForm_text_input ("<b>Num Horizontal Gridlines</b><br><small>(Blank for auto)</small>", "graph_h_num_grid", sBLK, false)

			container+=  hubiForm_help()
			hubiForm_container(container, 1)
		}

		//Vertical Axis
		hubiForm_section("Vertical Axis", 1, sBLK, sBLK){
			container=[]
			container << hubiForm_font_size ((sTIT): "Vertical Axis", (sNM): "graph_vaxis", default: 9, min: 2, max: 20)
			container << hubiForm_color ("Vertical Header", "graph_vh", "#000000", false)
			container << hubiForm_color ("Vertical Axis", "graph_va", "#C0C0C0", false)
			hubiForm_container(container, 1)
		}

		//Left Axis
		hubiForm_section("Left Axis", 1, "arrow_back", sBLK){
			container=[]
			container << hubiForm_text_input("<b>Minimum for left axis</b><small>(Blank for auto)</small>", "graph_vaxis_1_min", sBLK, false)
			container << hubiForm_text_input("<b>Maximum for left axis</b><small>(Blank for auto)</small>", "graph_vaxis_1_max", sBLK, false)
			container << hubiForm_text_input("<b>Num Vertical Gridlines</b><br><small>(Blank for auto)</small>", "graph_vaxis_1_num_lines", sBLK, false)
			container << hubiForm_switch	((sTIT): "<b>Show Left Axis Label on Graph</b>", (sNM): "graph_show_left_label", default: false, submit_on_change: true)
			if(graph_show_left_label==true){
				container << hubiForm_text_input ("<b>Input Left Axis Label</b>", "graph_left_label", "Left Axis Label", false)
				container << hubiForm_font_size ((sTIT): "Left Axis", (sNM): "graph_left", default: 9, min: 2, max: 20)
				container << hubiForm_color	("Left Axis", "graph_left", "#FFFFFF", false)
			}
			hubiForm_container(container, 1)
		}

		//Right Axis
		hubiForm_section("Right Axis", 1, "arrow_forward", sBLK){
			container=[]
			container << hubiForm_text_input("<b>Minimum for right axis</b><small>(Blank for auto)</small>", "graph_vaxis_2_min", sBLK, false)
			container << hubiForm_text_input("<b>Maximum for right axis</b><small>(Blank for auto)</small>", "graph_vaxis_2_max", sBLK, false)
			container << hubiForm_text_input("<b>Num Vertical Gridlines</b><br><small>(Blank for auto)</small>", "graph_vaxis_2_num_lines", sBLK, false)
			container << hubiForm_switch	((sTIT): "<b>Show Right Axis Label on Graph</b>", (sNM): "graph_show_right_label", default: false, submit_on_change: true)
			if(graph_show_right_label==true){
				container << hubiForm_text_input ("<b>Input right Axis Label</b>", "graph_right_label", "Right Axis Label", false)
				container << hubiForm_font_size ((sTIT): "Right Axis", (sNM): "graph_right", default: 9, min: 2, max: 20)
				container << hubiForm_color	("Right Axis", "graph_right", "#FFFFFF", false)
			}
			hubiForm_container(container, 1)
		}

		//Legend
		hubiForm_section("Legend", 1, sBLK, sBLK){
			container=[]
			List<Map> legendPosition=[["top": "Top"], ["bottom":"Bottom"], ["in": "Inside Top"]]
			List<Map> insidePosition=[["start": "Left"], ["center": "Center"], ["end": "Right"]]
			container << hubiForm_switch((sTIT): "Show Legend on Graph", (sNM): "graph_show_legend", default: false, submit_on_change: true)
			if(graph_show_legend==true){
				container << hubiForm_font_size ((sTIT): "Legend", (sNM): "graph_legend", default: 9, min: 2, max: 20)
				container << hubiForm_color	("Legend", "graph_legend", "#000000", false)
				hubiForm_container(container, 1)
				input( (sTYPE): sENUM, (sNM): "graph_legend_position",(sTIT): "<b>Legend Position</b>", defaultValue: "bottom", options: legendPosition)
				input( (sTYPE): sENUM, (sNM): "graph_legend_in side_position",(sTIT): "<b>Legend Justification</b>", defaultValue: "center", options: insidePosition)
			}else{
				hubiForm_container(container, 1)
			}
		}

		state.num_devices=0
		if(dataSources){
			Integer i; i=0
			for(Map ent in dataSources){
				i++
			}
			state.num_devices=i
		}
		List<Map> availableAxis
		availableAxis=[["0" : "Left Axis"], ["1": "Right Axis"]]
		if(state.num_devices == 1){
			availableAxis=[["0" : "Left Axis"], ["1": "Right Axis"], ["2": "Both Axes"]]
		}

		//Line
		Integer cnt
		cnt=0

		if(dataSources){
			for(Map ent in dataSources){

				String sid=sMs(ent,sID)
				String attribute=sMs(ent,sA)
				String dn=sMs(ent,sDISPNM)

				hubiForm_section("${sLblTyp(ent[sT])}${dn} - ${attribute}", 1, sBLK,sid+attribute){

					container=[]
					input( (sTYPE): sENUM, (sNM): "graph_axis_number_${sid}_${attribute}",(sTIT): "<b>Graph Axis Side</b>", defaultValue: s0, options: availableAxis)
					container << hubiForm_color("Line",
							"graph_line_${sid}_${attribute}",
							hubiTools_rotating_colors(cnt),
							false)
					container << hubiForm_line_size((sTIT): "Line Thickness",
							(sNM): "attribute_${sid}_${attribute}",
							default: 2, min: 1, max: 20)

		//TODO figure out from data if there are choices
					String startVal, endVal
					startVal=sBLK
					endVal=sBLK
					Map a=gtStartEndTypes(ent,attribute)
					if(a){
						startVal=a.start
						endVal=a.end
					}
				//	String startVal=supportedTypes[attribute] ? supportedTypes[attribute].start : ""
				//	String endVal=supportedTypes[attribute] ? supportedTypes[attribute].end : ""

					if(graph_type == "Area Graph"){
						container << hubiForm_slider ((sTIT): "Opacity of the area below the line",
								(sNM): "attribute_${sid}_${attribute}_opacity",
								default: 30,
								min: 0,
								max: 100,
								units: "%",
								submit_on_change: false)
					}
					String nnvars= "attribute_${sid}_${attribute}_non_number".toString()
					String svars= "attribute_${sid}_${attribute}_startString".toString()
					String evars= "attribute_${sid}_${attribute}_endString".toString()
					if(startVal != sBLK){
						app.updateSetting (nnvars, true)
						app.updateSetting (svars, startVal)
						app.updateSetting (evars, endVal)
						container << hubiForm_text("<b><mark>This Attribute ($attribute) is non-numerical, please choose values for the states below</mark></b>")

						container << hubiForm_text_input("Value for <mark>$startVal</mark>",
								"attribute_${sid}_${attribute}_${startVal}",
								"100", false)

						container << hubiForm_text_input("Value for <mark>$endVal</mark>",
								"attribute_${sid}_${attribute}_${endVal}",
								s0, false)
						hubiForm_container(container, 1)

					}else{
						wremoveSetting(nnvars)
						wremoveSetting(svars)
						wremoveSetting(evars)
						container << hubiForm_switch((sTIT): "Display as a Drop Line", (sNM): "attribute_${sid}_${attribute}_drop_line", default: false, submit_on_change: true)

						if(settings["attribute_${sid}_${attribute}_drop_line"]==true){
							container << hubiForm_text_input("Value to drop the Line",
									"attribute_${sid}_${attribute}_drop_value",
									s0, false)
							hubiForm_container(container, 1)
							input( (sTYPE): sENUM, (sNM): "attribute_${sid}_${attribute}_drop_time",(sTIT): "Drop Line Time", defaultValue: "300000", options: timespanEnum2 )

						}else{
							hubiForm_container(container, 1)
						}
					}
					cnt += 1
				}
			}
		}
	}
}

String getData_linegraph(){
	Map resp=[:]

	Date then
	then=new Date()

	Long graph_time
	use (TimeCategory){
		then -= Integer.parseInt(graph_timespan).milliseconds
		graph_time=then.getTime()
	}

//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)
			String sa="${sid}_${attribute}".toString()

			resp[sid]= resp[sid] ?: [:]
			List tEvents

			List<Map> respEvents
			List<Map> data=CgetData(ent, then)
			//return [date: d, (sVAL): sum.round(decimals), t: d.getTime()]

//				log.warn "got dn: $dn attribute: $attribute	data1: $data"
			List<Map>data1
			data1=data.collect{ Map it -> [(sDT): it[sT], (sVAL): getValue(sid, attribute, it[sVAL])]}

			List<Map> data2
			data2=data1.findAll{  Map it ->(Long)it[sDT] > graph_time}

			List<Map> temp
			temp=([]+data2)// as List<Map>
				//temp=temp.sort{ (Long)it[sDT] }
			respEvents=temp

			data1=null
			data2=null
//				log.warn "FINAL got sensor: $sensor attribute: $attribute	data1: $temp"
			temp=null

/*
				respEvents << sensor.statesSince(attribute, then, [max: 50000]).collect{[ date: it.date.getTime(), (sVAL): getValue(sid, attribute, it.value) ]}
				respEvents=respEvents.flatten()
				respEvents=respEvents.reverse()
*/
				//Add drop lines for non-numerical devices
			if(settings["attribute_${sa}_non_number"] && respEvents.size()>1){
				String start=settings["attribute_${sa}_startString"]
				String end=settings["attribute_${sa}_endString"]
				Float startVal=Float.parseFloat((String)settings["attribute_${sa}_${start}".toString()])
				Float endVal=Float.parseFloat((String)settings["attribute_${sa}_${end}".toString()])
				tEvents=[]
				//Add Start Event
				Long currDate
				currDate=then.getTime()
				if(respEvents[0][sVAL] == startVal){
					tEvents.push([(sDT): currDate, (sVAL): endVal])
				}else{
					tEvents.push([(sDT): currDate, (sVAL): startVal])
				}
				Integer i
				for(i=0; i<respEvents.size(); i++){
					currDate=(Long)respEvents[i][sDT]
					if(respEvents[i][sVAL] == startVal){
						tEvents.push([(sDT): currDate-1000L, (sVAL): endVal])

					}else{
						tEvents.push([(sDT): currDate-1000L, (sVAL): startVal])
					}
					tEvents.push(respEvents[i])
				}
				respEvents=tEvents
			}

			//graph_max_points
			if(graph_max_points > 0){
				Integer reduction=Math.ceil(respEvents.size() / graph_max_points.toDouble()).toInteger()
				respEvents=respEvents.collate(reduction).collect{ List group ->
					group.inject([ (sDT): 0, (sVAL): 0 ]){ col, it ->
						col[sDT] += it[sDT] / group.size()
						col[sVAL] += it[sVAL] / group.size()
						return col
					}
				}
			}

			//add drop line data
			tEvents=[]
			if(settings["attribute_${sa}_drop_line"] && respEvents.size()>1){
				def curr, prev
				Long currDate, prevDate

				String drop_time=settings["attribute_${sa}_drop_time"]
				String drop_value=settings["attribute_${sa}_drop_value"]
				tEvents.push(respEvents[0])
				Integer i
				for(i=1; i<respEvents.size(); i++){
					curr=respEvents[i]
					prev=respEvents[i-1]
					currDate=(Long)curr[sDT]
					prevDate=(Long)prev[sDT]

					if((currDate - prevDate) > Integer.parseInt(drop_time)){
						//add first zero
						tEvents.push([(sDT): prevDate-1000L, (sVAL): Float.parseFloat(drop_value)])
						tEvents.push([(sDT): currDate+1000L, (sVAL): Float.parseFloat(drop_value)])
					}
					tEvents.push(curr)
				}
				respEvents=tEvents
			}
			resp[sid][attribute]=respEvents
		}
	}

	return JsonOutput.toJson(resp)
}

Map getOptions_linegraph(){

	Map options=[
			"graphReduction": graph_max_points,
			"graphTimespan": Integer.parseInt(graph_timespan),
			"graphUpdateRate": Integer.parseInt(graph_update_rate),
			"graphOptions": [
					"width": graph_static_size ? graph_h_size : "100%",
					"height": graph_static_size ? graph_v_size: "100%",
					"chartArea": [ "width": graph_static_size ? graph_h_size : "80%", "height": graph_static_size ? graph_v_size: "80%"],
					"hAxis": ["textStyle": ["fontSize": graph_haxis_font,
											"color": graph_hh_color_transparent ? "transparent" : graph_hh_color ],
							"gridlines": ["color": graph_ha_color_transparent ? "transparent" : graph_ha_color,
											"count": graph_h_num_grid != "" ? graph_h_num_grid : null
							],
							"format":	graph_h_format==""?"":graph_h_format
					],
					"vAxis": ["textStyle": ["fontSize": graph_vaxis_font,
											"color": graph_vh_color_transparent ? "transparent" : graph_vh_color],
							"gridlines": ["color": graph_va_color_transparent ? "transparent" : graph_va_color],
					],
					"vAxes": [
							0: ["title" : graph_show_left_label ? graph_left_label: null,
								"titleTextStyle": ["color": graph_left_color_transparent ? "transparent" : graph_left_color, "fontSize": graph_left_font],
								"viewWindow": ["min": graph_vaxis_1_min != "" ? graph_vaxis_1_min : null,
											"max": graph_vaxis_1_max != "" ? graph_vaxis_1_max : null],
								"gridlines": ["count" : graph_vaxis_1_num_lines != "" ? graph_vaxis_1_num_lines : null ],
								"minorGridlines": ["count" : 0]
							],

							1: ["title": graph_show_right_label ? graph_right_label : null,
								"titleTextStyle": ["color": graph_right_color_transparent ? "transparent" : graph_right_color, "fontSize": graph_right_font],
								"viewWindow": ["min": graph_vaxis_2_min != "" ? graph_vaxis_2_min : null,
											"max": graph_vaxis_2_max != "" ? graph_vaxis_2_max : null],
								"gridlines": ["count" : graph_vaxis_2_num_lines != "" ? graph_vaxis_2_num_lines : null ],
								"minorGridlines": ["count" : 0]
							]

					],
					"legend": !graph_show_legend ? ["position": sNONE] : ["position": graph_legend_position,
																		"alignment": graph_legend_inside_position,
																		"textStyle": ["fontSize": graph_legend_font,
																						"color": graph_legend_color_transparent ? "transparent" : graph_legend_color]],
					"backgroundColor": graph_background_color_transparent ? "transparent" : graph_background_color,
					"curveType": !graph_smoothing ? "" : "function",
					"title": !graph_show_title ? "" : graph_title,
					"titleTextStyle": !graph_show_title ? "" : ["fontSize": graph_title_font, "color": graph_title_color_transparent ? "transparent" : graph_title_color],
					"titlePosition" : graph_title_inside ? "in" : "out",
					"interpolateNulls": true, //for null vals on our chart
					"orientation" : graph_y_orientation == true ? "vertical" : "horizontal",
					"reverseCategories" : graph_x_orientation,
					"series": [],

			]
	]

//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)
			String sa="${sid}_${attribute}".toString()

			//add colors and thicknesses
			Integer axis=Integer.parseInt(settings["graph_axis_number_${sa}"].toString())
			String text_color=settings["graph_line_${sa}_color"]
			String text_color_transparent=settings["graph_line_${sa}_color_transparent"]
			Integer line_thickness=(Integer)settings["attribute_${sa}_line_size"]
			Float opacity
			opacity=0.0
			if(settings["attribute_${sa}_opacity"]){
				opacity=settings["attribute_${sa}_opacity"]/100.0
			}

			Map annotations=[
					"targetAxisIndex": axis,
					"color": text_color_transparent ? "transparent" : text_color,
					"stroke": text_color_transparent ? "transparent" : "red",
					"lineWidth": line_thickness,
					"areaOpacity" : opacity
			]

			options.graphOptions.series << annotations
		}
	}

	return options
}

String getDrawType_linegraph(){
	switch (graph_type){
		case "Line Graph": return "google.visualization.LineChart"
		case "Area Graph": return "google.visualization.AreaChart"
		case "Scatter Plot": return "google.visualization.ScatterChart"
	}
	return 'bad'
}

String getGraph_linegraph(){
	String fullSizeStyle="margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden"

	String html="""
<html style="${fullSizeStyle}">
	<link rel='icon' href='https://www.shareicon.net/data/256x256/2015/09/07/97252_barometer_512x512.png' type='image/x-icon'/>
	<link rel="apple-touch-icon" href="https://www.shareicon.net/data/256x256/2015/09/07/97252_barometer_512x512.png">
	<head>
${scriptIncludes()}
		<script src="https://cdnjs.cloudflare.com/ajax/libs/svg.js/3.0.16/svg.min.js" integrity="sha256-MCvBrhCuX8GNt0gmv06kZ4jGIi1R2QNaSkadjRzinFs=" crossorigin="anonymous"></script>
		<script type="text/javascript">
google.charts.load('current',{'packages':['corechart']});

let options=[];
let subscriptions={};
let graphData={};

//stack for accumulating points to average
let stack={};

let websocket;

class Loader{
	constructor(){
		this.elem=jQuery(jQuery(document.body).prepend(`
			<div class="loaderContainer">
				<div class="dotsContainer">
					<div class="dot"></div>
					<div class="dot"></div>
					<div class="dot"></div>
				</div>
				<div class="text"></div>
			</div>
		`).children()[0]);
	}

	setText(text){
		this.elem.find('.text').text(text);
	}

	remove(){
		this.elem.remove();
	}
}

function getOptions(){
	return jQuery.get("${state.localEndpointURL}getOptions/?access_token=${state.endpointSecret}", (data) =>{
		options=data;
		console.log("Got Options");
		console.log(options);
	});
}

function getSubscriptions(){
	return jQuery.get("${state.localEndpointURL}getSubscriptions/?access_token=${state.endpointSecret}", (data) =>{
		console.log("Got Subscriptions");
		console.log(data);
		subscriptions=data;

	});
}

function getGraphData(){
	return jQuery.get("${state.localEndpointURL}getData/?access_token=${state.endpointSecret}", (data) =>{
		console.log("Got Graph Data");
		console.log(data);
		graphData=data;
	});
}

function parseEvent(event){
	const now=new Date().getTime();
	let odeviceId=event.deviceId;
	let deviceId="d"+odeviceId;

	//only accept relevent events
	if(subscriptions.ids.includes(deviceId) && subscriptions.attributes[deviceId].includes(event.name)){
		let value=event.value;
		let attribute=event.name;

		non_num=subscriptions.non_num[deviceId][attribute];

		if(non_num.valid){
			if(value == non_num.start){
				graphData[deviceId][attribute].push({ date: now-1000, value: non_num.endVal});
				graphData[deviceId][attribute].push({ date: now, value: non_num.startVal});
			} else if (value == non_num.end){
				graphData[deviceId][attribute].push({ date: now-1000, value: non_num.startVal});
				graphData[deviceId][attribute].push({ date: now, value: non_num.endVal});
			}
		} else{
			stack[deviceId][attribute].push({ date: now, value: value });

			//check the stack
			const graphEvents=graphData[deviceId][attribute];
			const stackEvents=stack[deviceId][attribute];
			const span=graphEvents[1].date - graphEvents[0].date;

			if(stackEvents[stackEvents.length - 1].date - graphEvents[graphEvents.length - 1].date >= span
				|| (stackEvents.length > 1
				&& stackEvents[stackEvents.length - 1].date - stackEvents[0].date >= span)){

				//push the stack
				graphData[deviceId][attribute].push(stack[deviceId][attribute].reduce((accum, it) => accum={ date: accum.date + it.date / stackEvents.length, value: accum.value + it.value / stackEvents.length },{ date: 0, value: 0.0 }));
				stack[deviceId][attribute]=[];

				//check for drop
				const thisDrop=subscriptions.drop[deviceId][attribute];
				const thisEvents=graphData[deviceId][attribute];
				if(thisDrop.valid && thisEvents[thisEvents.length - 2].date - thisEvents[thisEvents.length - 1].date > thisDrop.time){
					graphData[deviceId][attribute].splice(thisEvents.length - 2, 0,{ date: thisEvents[thisEvents.length - 2].date + 1000, value: thisDrop.value });
					graphData[deviceId][attribute].splice(thisEvents.length - 2, 0,{ date: thisEvents[thisEvents.length - 1].date - 1000, value: thisDrop.value });
				}
			}
		}

		//update if we are realtime
		if(options.graphUpdateRate === 0) update();
	}
}

async function aupdate(){
	await getGraphData();
	//drawChart();
	update();
}

function update(callback){
	//boot old data
	let min=new Date().getTime();
	min -= options.graphTimespan;

	Object.entries(graphData).forEach(([deviceId, attributes]) =>{
		Object.entries(attributes).forEach(([attribute, events]) =>{
			//filter old events
			graphData[deviceId][attribute]=events.filter(it => it.date > min);
		});
	});

	drawChart(callback);
}

async function onLoad(){
	//append our css
	jQuery(document.head).append(`
		<style>
			.loaderContainer{
				position: fixed;
				z-index: 100;

				width: 100%;
				height: 100%;

				background-color: white;

				display: flex;
				flex-flow: column nowrap;
				justify-content: center;
				align-items: middle;
			}

			.dotsContainer{
				height: 60px;
				padding-bottom: 10px;

				display: flex;
				flex-flow: row nowrap;
				justify-content: center;
				align-items: flex-end;
			}

			@keyframes bounce{
				0%{
					transform: translateY(0);
				}

				50%{
					transform: translateY(-50px);
				}

				100%{
					transform: translateY(0);
				}
			}

			.dot{
				box-sizing: border-box;

				margin: 0 25px;

				width: 10px;
				height: 10px;

				border: solid 5px black;
				border-radius: 5px;

				animation-name: bounce;
				animation-duration: 1s;
				animation-iteration-count: infinite;
			}

			.dot:nth-child(1){
				animation-delay: 0ms;
			}

			.dot:nth-child(2){
				animation-delay: 333ms;
			}

			.dot:nth-child(3){
				animation-delay: 666ms;
			}

			.text{
				font-family: Arial;
				font-weight: 200;
				font-size: 2rem;
				text-align: center;
			}
		</style>
	`);

	let loader=new Loader();

	//first load
	loader.setText('Getting options (1/4)');
	await getOptions();
	loader.setText('Getting device data (2/4)');
	await getSubscriptions();
	loader.setText('Getting events (3/4)');
	await getGraphData();

	loader.setText('Drawing chart (4/4)');

	//create stack
	Object.entries(graphData).forEach(([deviceId, attrs]) =>{
		stack[deviceId]={};
		Object.keys(attrs).forEach(attr =>{
			stack[deviceId][attr]=[];
		});
	})

	update(() =>{
		//destroy loader when we are done with it
		loader.remove();
	});

	if(subscriptions.id=='poll'){
		if(options.graphUpdateRate > 0){
			setInterval(() =>{
				aupdate();
			}, options.graphUpdateRate);
		}
	} else{
		//start our update cycle
		if(options.graphUpdateRate !== -1){
			//start websocket
			websocket=new WebSocket("ws://" + location.hostname + "/eventsocket");
			websocket.onopen=() =>{
				console.log("WebSocket Opened!");
			}
			websocket.onmessage=(event) =>{
				parseEvent(JSON.parse(event.data));
			}

			if(options.graphUpdateRate !== 0){
				setInterval(() =>{
					update();
				}, options.graphUpdateRate);
			}
		}
	}

	//attach resize listener
	window.addEventListener("resize", () =>{
		drawChart();
	});
}

function onBeforeUnload(){
	if(websocket) websocket.close();
}

function drawChart(callback){
	let now=new Date().getTime();
	let min=now - options.graphTimespan;

	let dataTable=new google.visualization.DataTable();
	dataTable.addColumn({ label: 'Date', type: 'datetime' });

	let colNums={};

	let i=0;
	subscriptions.ids.forEach((deviceId) =>{
		colNums[deviceId]={};
		subscriptions.attributes[deviceId].forEach((attr) =>{

			dataTable.addColumn({ label: subscriptions.labels[deviceId][attr].replace('%deviceName%', subscriptions.sensors[deviceId].displayName).replace('%attributeName%', attr), type: 'number' });
			colNums[deviceId][attr]=i++;
		});
	});

	const totalCols=i;

	let parsedGraphData=[];
	//map the graph data
	Object.entries(graphData).forEach(([deviceIndex, attributes]) =>{
		Object.entries(attributes).forEach(([attribute, events]) =>{
			non_num=subscriptions.non_num[deviceIndex][attribute];
			var length=events.length;
			events.forEach((event) =>{

				//Make a new entry
				let newEntry=Array.apply(null, new Array(totalCols + 1));
				newEntry[0]=event.date;
				newEntry[colNums[deviceIndex][attribute] + 1]=event.value;
				parsedGraphData.push(newEntry);

			});

		});
	});

	//map the stack
	Object.entries(stack).forEach(([deviceIndex, attributes]) =>{
		Object.entries(attributes).forEach(([attribute, events]) =>{
			if(events.length > 0){
				const event=events.reduce((accum, it) => accum={ date: accum.date, value: accum.value + it.value / events.length },{ date: now, value: 0.0 });

				let newEntry=Array.apply(null, new Array(totalCols + 1));
				newEntry[0]=event.date;
				newEntry[colNums[deviceIndex][attribute] + 1]=event.value;
				parsedGraphData.push(newEntry);
			}

		});
	});

	parsedGraphData=parsedGraphData.map((it) => [ moment(it[0]).toDate(), ...it.slice(1).map((it) => parseFloat(it)) ]);

	parsedGraphData.forEach(it =>{
		dataTable.addRow(it);
	});


	let graphOptions=Object.assign({}, options.graphOptions);

	graphOptions.hAxis=Object.assign(graphOptions.hAxis,{ viewWindow:{ min: moment(min).toDate(), max: moment(now).toDate() } });

	let chart=new ${drawType_linegraph()}(document.getElementById("timeline"));

	//if we have a callback
	if(callback) google.visualization.events.addListener(chart, 'ready', callback);

	chart.draw(dataTable, graphOptions);
}

google.charts.setOnLoadCallback(onLoad);
window.onBeforeUnload=onBeforeUnload;

		</script>
	</head>
	<body style="${fullSizeStyle}">
		<div id="timeline" style="${fullSizeStyle}" align="center"></div>
	</body>

</html>
	"""

	return html
}

//oauth endpoints

Map getSubscriptions_linegraph(){
	List<String> ids=[]
	Map sensors_=[:]
	Map attributes=[:]
	Map labels=[:]
	Map drop_=[:]
	Map non_num_=[:]

	Boolean isPoll
	isPoll=(Boolean)state.hasFuel

//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)
			String dn=sMs(ent,sDISPNM)
			//String typ=((String) ent[sT]).capitalize()
			String sa="${sid}_${attribute}".toString()

			String attr=attribute

			if(!ids.contains(sid)) ids << sid // sensor.idAsLong

		//only take what we need
		//Map sensors_fmt=gtSensorFmt()
			sensors_[sid]=[ (sID): sid /* , idAsLong: sensor.idAsLong */, displayName: dn ]

			attributes[sid]= attributes[sid] ?: []
			attributes[sid] << attribute

			labels[sid]= labels[sid] ?: [:]
			labels[sid][attr]=settings["graph_name_override_${sa}"]
			labels[sid][attr]=settings["graph_name_override_${sa}"]

			drop_[sid]= drop_[sid] ?: [:]
			non_num_[sid]= non_num_[sid] ?: [:]

			if(settings["attribute_${sa}_non_number"]==true){
				String startString=settings["attribute_${sa}_startString"]
				String endString=settings["attribute_${sa}_endString"]
				non_num_[sid][attr]=[ valid: true,
									start:	startString,
									startVal:	settings["attribute_${sa}_${startString}"],
									end:		endString,
									endVal:	settings["attribute_${sa}_${endString}"]
				]
			}else{
				non_num_[sid][attr]=[ valid: false,
										start: sBLK,
										end: ""]
			}

			drop_[sid][attr]=[valid: settings["attribute_${sa}_drop_line"],
								time: settings["attribute_${sa}_drop_time"],
								(sVAL): settings["attribute_${sa}_drop_value"]]
		}
	}

	Map subscriptions=[
			(sID): isPoll ? 'poll' : 'sensor',
			ids: ids,
			sensors: sensors_,
			attributes: attributes,
			labels : labels,
			drop : drop_,
			non_num: non_num_
	]

	return subscriptions
}







/*
 * TODO: Rangebar methods
 */

def mainRangebar(){
	mainShare1("Choose Numeric Attribute Only",'graph_timespan')
}

def deviceRangebar(){
	deviceShare1(true,true,false)
}

def attributeRangebar(){

	List<Map> dataSources= createDataSources(true)
	//state.count_=0
	dynamicPage(name: "attributeConfigurationPage", nextPage:"graphSetupPage"){
		List container

		hubiForm_section("Graph Order", 1, "directions", sBLK){
			hubiForm_list_reorder("graph_order", "background", "#3e4475")
		}
//	TODO
		if(dataSources){
			for(Map ent in dataSources){

				String sid=sMs(ent,sID)
				String rid=ent.rid.toString()
				String attribute=sMs(ent,sA)
				String dn=sMs(ent,sDISPNM)
				String typ=sMs(ent,sT).capitalize()
				String hint= typ=='Fuel' ? " (Canister ${ent.c} Name ${ent.n})" : sBLK
				String sa="${sid}_${attribute}".toString()

//				Integer cnt=1
				//state.count_++
				hubiForm_section("${sLblTyp(ent[sT])}${dn} - ${attribute}${hint}", 1, "direction",sid+attribute){
					container=[]

					if(typ=='Sensor'){
						String tvar="var_"+sa+"_lts"
						if((Boolean)parent.ltsAvailable(rid, attribute)){
							container << hubiForm_sub_section("Long Term Storage in use")

						}else{
							app.updateSetting(tvar, false)
							settings[tvar]= false
						}
					}

					container << hubiForm_text_input("<b>Override ${typ} Name</b><small></i><br>Use %deviceName% for DEVICE and %attributeName% for ATTRIBUTE</i></small>",
							"graph_name_override_${sa}",
							"%deviceName%: %attributeName%", false)

					container << hubiForm_color	("Bar Background",		"attribute_${sa}_background","#5b626e", false, true)
					container << hubiForm_color	("Min/Max",			"attribute_${sa}_minmax", "#607c91", false)
					container << hubiForm_color	("Current Value",		"attribute_${sa}_current", "#8eb6d4", false)
					container << hubiForm_color	("Current Value Border", "attribute_${sa}_current_border", "#FFFFFF", false)
					container << hubiForm_switch ((sTIT): "Show Current Value on Bar?", (sNM): "attribute_${sa}_show_value", default: false, submit_on_change: true)
					if(settings["attribute_${sa}_show_value"]==true){
						container << hubiForm_text_input("Units", "attribute_${sa}_annotation_units", sBLK, false)
					}
					hubiForm_container(container, 1)
				}
				//cnt += 1
			}
		}
	}
}

def graphRangebar(){

	List timespanEnum1=[[0:"Live"], [1:"Hourly"], [2:"Daily"], [3:"Every Three Days"], [4:"Weekly"]]

	dynamicPage(name: "graphSetupPage"){
		List container
		hubiForm_section("General Options", 1, sBLK, sBLK){
			container=[]
			input( (sTYPE): sENUM, (sNM): "graph_type",(sTIT): "<b>Select graph type</b>", multiple: false, required: false, options: [["1": "Bar Chart"],["2": "Column Chart"]], defaultValue: s1)
			inputGraphUpdateRate()
			input( (sTYPE): sENUM, (sNM): "graph_timespan",(sTIT): "<b>Select Time span to Graph (i.e How Often to Reset Range)</b>", multiple: false, required: false, options: timespanEnum1, defaultValue: "2", submitOnChange: true)

			container << hubiForm_color ("Graph Background", "graph_background", "#FFFFFF", false)
			container << hubiForm_slider ((sTIT): "Graph Bar Width (1%-100%)", (sNM): "graph_bar_percent", default: 90, min: 1, max: 100, units: "%")
			container << hubiForm_text_input("Graph Max", "graph_max", "100", false)
			container << hubiForm_text_input("Graph Min", "graph_min", s0, false)

			hubiForm_container(container, 1)
		}
		hubiForm_section("Axes", 1, sBLK, sBLK){
			container=[]
			container << hubiForm_color ("Axis", "haxis", "#000000", false)
			container << hubiForm_font_size ((sTIT): "Axis", (sNM): "haxis", default: 9, min: 2, max: 20)
			container << hubiForm_slider ((sTIT): "Number of Pixels for Axis", (sNM): "graph_h_buffer", default: 40, min: 10, max: 500, units: " pixels")
			hubiForm_container(container, 1)
		}
		hubiForm_section("Device Names", 1, sBLK, sBLK){
			container=[]
			container << hubiForm_font_size ((sTIT): "Device Name", (sNM): "graph_axis", default: 9, min: 2, max: 20)
			container << hubiForm_color ("Device Name","graph_axis", "#000000", false)
			container << hubiForm_slider ((sTIT): "Number of Pixels for Device Name Area", (sNM): "graph_v_buffer", default: 100, min: 10, max: 500, units: " pixels")

			hubiForm_container(container, 1)
		}
		hubiForm_section("Graph Size", 1, sBLK, sBLK){
			container=[]
			input( (sTYPE): sBOOL, (sNM): "graph_static_size",(sTIT): "<b>Set size of Graph?</b><br><small>(False=Fill Window)</small>", defaultValue: false, submitOnChange: true)
			if((Boolean)settings.graph_static_size){
				container << hubiForm_slider ((sTIT): "Horizontal dimension of the graph", (sNM): "graph_h_size", default: 800, min: 100, max: 3000, units: " pixels", submit_on_change: false)
				container << hubiForm_slider ((sTIT): "Vertical dimension of the graph", (sNM): "graph_v_size", default: 600, min: 100, max: 3000, units: " pixels", submit_on_change: false)
			}

			hubiForm_container(container, 1)
		}
		hubiForm_section("Annotations", 1, sBLK, sBLK){
			container=[]
			container << hubiForm_font_size ((sTIT): "Annotation", (sNM): "annotation", default: 16, min: 2, max: 40)
			container << hubiForm_switch	((sTIT): "Show Annotation Outside (true) or Inside (false) of Bars", (sNM): "annotation_inside", default: false)
			container << hubiForm_color	("Annotation", "annotation", "#000000", false)
			container << hubiForm_color	("Annotation Aura", "annotation_aura", "#FFFFFF", false)
			container << hubiForm_switch	((sTIT): "Bold Annotation", (sNM): "annotation_bold", default: false)
			container << hubiForm_switch	((sTIT): "Italic Annotation", (sNM): "annotation_italic", default: false)

			hubiForm_container(container, 1)
		}
	}
}

String getData_rangebar(){
	Map resp=[:]

	Date then
	then=new Date()

	switch ((String)settings.graph_timespan){
		case s0: //"Live":
			break
		case s1: //"Hourly":
			use (TimeCategory){
				then -= 1.hours
			}
			break
		case "2": //"Daily":
			then.setHours(0)
			then.setMinutes(0)
			then.setSeconds(0)
			break
		case "3": //"Every Three Days":
			use (TimeCategory){
				then -= 2.days
			}
			then.setHours(0)
			then.setMinutes(0)
			then.setSeconds(0)
			break
		case "4": //"Weekly":
			use (TimeCategory){
				then -= 6.days
			}
			then.setHours(0)
			then.setMinutes(0)
			then.setSeconds(0)
			break
	}
	//Long graph_time=then.getTime()

//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)

			resp[sid]= resp[sid] ?: [:]

			List<Map> data=CgetData(ent, then)

//			log.warn "got sensor: $sensor attribute: $attribute data1: $data"
			//List data1=data.findAll{ (Long)it.date > graph_time}
			List<Double> temp=data.collect{ Map it -> getValue(sid,attribute,it[sVAL]) }

			//List temp=sensor.statesSince(attribute, then, [max: 1000]).collect{ it.getFloatValue() }
			Integer sz=data.size()
			//Float v= sensor.currentState(attribute).getFloatValue()
			Float v= "${data[sz-1][sVAL]}".toFloat()

			if(temp.size() == 0){
				resp[sid][attribute]=[current: v, min: v, max: v]
			}else{
				resp[sid][attribute]=[current: v, min: temp.min(), max: temp.max()]
			}
		}
	}
	return JsonOutput.toJson(resp)
}

Map getOptions_rangebar(){

	List colors=[]
//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)

			String attrib_string="attribute_${sid}_${attribute}_color"
			String transparent_attrib_string="attribute_${sid}_${attribute}_color_transparent"
			colors << (settings[transparent_attrib_string] ? "transparent" : settings[attrib_string])

		}
	}

	String axis1
	String axis2
	if((String)settings.graph_type == s1){
		axis1="hAxis"
		axis2="vAxis"
	}else{
		axis1="vAxis"
		axis2="hAxis"
	}

	Map options=[
			"graphTimespan": Integer.parseInt(settings.graph_timespan),
			"graphUpdateRate": Integer.parseInt(settings.graph_update_rate),
			(sGRAPHT): Integer.parseInt(sMs(settings,'graph_type')),
			"graphOptions": [
					"bar" : [ "groupWidth" : "${settings.graph_bar_percent}%",
					],
					"width": (Boolean)settings.graph_static_size ? settings.graph_h_size : "100%",
					"height": (Boolean)settings.graph_static_size ? settings.graph_v_size: "90%",
					"timeline": [
							"rowLabelStyle": ["fontSize": settings.graph_axis_font, "color": settings.graph_axis_color_transparent ? "transparent" : settings.graph_axis_color],
							"barLabelStyle": ["fontSize": settings.graph_axis_font]
					],
					"backgroundColor": graph_background_color_transparent ? "transparent" : graph_background_color,
					"isStacked": true,
					"chartArea": [ "left": settings.graph_type == s1 ? settings.graph_v_buffer : settings.graph_h_buffer,
								"right" : 10,
								"top": 10,
								"bottom": settings.graph_type == s1 ? settings.graph_h_buffer : settings.graph_v_buffer ],
					"legend" : [ "position" : sNONE ],
					(axis1): [ "viewWindow" : ["max" : graph_max,
											"min" : graph_min],
							"minValue" : graph_min,
							"maxValue" : graph_max,
							"textStyle" : ["color": haxis_color_transparent ? "transparent" : haxis_color,
											"fontSize": haxis_font]
					],
					(axis2): [ "textStyle" : ["color": graph_axis_color_transparent ? "transparent" : graph_axis_color,
											"fontSize": graph_axis_font]
					],
					"annotations" : [	"alwaysOutside": true,
										"textStyle": [
												"fontSize": annotation_font,
												"bold":	annotation_bold,
												"italic": annotation_italic,
												"color":	annotation_color_transparent ? "transparent" : annotation_color,
												"auraColor":annotation_aura_color_transparent ? "transparent" : annotation_aura_color,
										],
										"stem": [ "color": "transparent" ],
										"highContrast": "false"
					],

			],
			"graphLow": graph_min,
			"graphHigh": graph_max,

	]
	return options
}

String getGraph_rangebar(){
	String fullSizeStyle="margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden"

	String html="""
<html style="${fullSizeStyle}">
		<head>
${scriptIncludes()}
			<script type="text/javascript">
google.charts.load('current',{'packages':['corechart']});

let options=[];
let subscriptions={};
let graphData={};

//stack for accumulating points to average
let stack={};

let websocket;

class Loader{
	constructor(){
		this.elem=jQuery(jQuery(document.body).prepend(`
			<div class="loaderContainer">
				<div class="dotsContainer">
					<div class="dot"></div>
					<div class="dot"></div>
					<div class="dot"></div>
				</div>
				<div class="text"></div>
			</div>
		`).children()[0]);
	}

	setText(text){
		this.elem.find('.text').text(text);
	}

	remove(){
		this.elem.remove();
	}
}

function getOptions(){
	return jQuery.get("${state.localEndpointURL}getOptions/?access_token=${state.endpointSecret}", (data) =>{
		options=data;
		console.log("Got Options");
		console.log(options);
	});
}

function getSubscriptions(){
	return jQuery.get("${state.localEndpointURL}getSubscriptions/?access_token=${state.endpointSecret}", (data) =>{
		console.log("Got Subscriptions");
		console.log(data);
		subscriptions=data;

	});
}

function getGraphData(){
	return jQuery.get("${state.localEndpointURL}getData/?access_token=${state.endpointSecret}", (data) =>{
		console.log("Got Graph Data");
		console.log(data);
		graphData=data;
	});
}

function parseEvent(event){
	let odeviceId=event.deviceId;
	let deviceId="d"+odeviceId;

	//only accept relevent events
	if(subscriptions.ids.includes(deviceId) && subscriptions.attributes[deviceId].includes(event.name)){
		let value=event.value;
		let attribute=event.name;

		console.log("Got Name: ", attribute, "Value: ", value);

		graphData[deviceId][attribute].current=value;
		if(value > graphData[deviceId][attribute].max) graphData[deviceId][attribute].max=value;
		else if (value < graphData[deviceId][attribute].min) graphData[deviceId][attribute].min=value;
		//update if we are realtime
		if(options.graphUpdateRate === 0) update();
	}
}

async function aupdate(){
	await getGraphData();
	//drawChart();
	update();
}

function update(callback){
	drawChart(callback);
}

async function onLoad(){
	//append our css
	jQuery(document.head).append(`
		<style>
			.loaderContainer{
				position: fixed;
				z-index: 100;

				width: 100%;
				height: 100%;

				background-color: white;

				display: flex;
				flex-flow: column nowrap;
				justify-content: center;
				align-items: middle;
			}

			.dotsContainer{
				height: 60px;
				padding-bottom: 10px;

				display: flex;
				flex-flow: row nowrap;
				justify-content: center;
				align-items: flex-end;
			}

			@keyframes bounce{
				0%{
					transform: translateY(0);
				}

				50%{
					transform: translateY(-50px);
				}

				100%{
					transform: translateY(0);
				}
			}

			.dot{
				box-sizing: border-box;

				margin: 0 25px;

				width: 10px;
				height: 10px;

				border: solid 5px black;
				border-radius: 5px;

				animation-name: bounce;
				animation-duration: 1s;
				animation-iteration-count: infinite;
			}

			.dot:nth-child(1){
				animation-delay: 0ms;
			}

			.dot:nth-child(2){
				animation-delay: 333ms;
			}

			.dot:nth-child(3){
				animation-delay: 666ms;
			}

			.text{
				font-family: Arial;
				font-weight: 200;
				font-size: 2rem;
				text-align: center;
			}
		</style>
	`);

	let loader=new Loader();

	//first load
	loader.setText('Getting options (1/4)');
	await getOptions();
	loader.setText('Getting device data (2/4)');
	await getSubscriptions();
	loader.setText('Getting events (3/4)');
	await getGraphData();
	loader.setText('Drawing chart (4/4)');

	update(() =>{
		//destroy loader when we are done with it
		loader.remove();
	});

	if(subscriptions.id=='poll'){
		if(options.graphUpdateRate > 0){
			setInterval(() =>{
				aupdate();
			}, options.graphUpdateRate);
		}
	} else{
		//start our update cycle
		if(options.graphUpdateRate !== -1){
			//start websocket
			websocket=new WebSocket("ws://" + location.hostname + "/eventsocket");
			websocket.onopen=() =>{
				console.log("WebSocket Opened!");
			}
			websocket.onmessage=(event) =>{
				parseEvent(JSON.parse(event.data));
			}

			if(options.graphUpdateRate !== 0){
				setInterval(() =>{
					update();
				}, options.graphUpdateRate);
			}
		}
	}

	//attach resize listener
	window.addEventListener("resize", () =>{
		drawChart();
	});
}

function onBeforeUnload(){
	if(websocket) websocket.close();
}

function drawChart(callback){
	let now=new Date().getTime();
	let min=now - options.graphTimespan;

	const dataTable=new google.visualization.arrayToDataTable([[{ type: 'string', label: 'Device' },{ type: 'number', label: 'na' },	{ role: "style" },{ role: "tooltip" },{ role: "annotation" },
																	{ type: 'number', label: 'nb' },	{ role: "style" },{ role: "tooltip" },{ role: "annotation" },
																	{ type: 'number', label: 'nc' },	{ role: "style" },{ role: "tooltip" },{ role: "annotation" },
																	{ type: 'number', label: 'nd'},	{ role: "style" },{ role: "tooltip" },{ role: "annotation" },
																	{ type: 'number', label: 'ne'},{ role: "style" },{ role: "tooltip" },{ role: "annotation" },
																	{ type: 'number', label: 'a'},	{ role: "style" },{ role: "tooltip" },{ role: "annotation" },
																	{ type: 'number', label: 'b'},	{ role: "style" },{ role: "tooltip" },{ role: "annotation" },
																	{ type: 'number', label: 'c' },	{ role: "style" },{ role: "tooltip" },{ role: "annotation" },
																	{ type: 'number', label: 'd' },	{ role: "style" },{ role: "tooltip" },{ role: "annotation" },
																	{ type: 'number', label: 'e' },	{ role: "style" },{ role: "tooltip" },{ role: "annotation" },
																]]);

	let globalMax=options.graphHigh;
	let globalMin=options.graphLow;
	subscriptions.order.forEach(orderStr =>{
		const splitStr=orderStr.split('_');
		const deviceId=splitStr[1];
		const attr=splitStr[2];
		const event=graphData[deviceId][attr];
		globalMax=globalMax < event.max ? event.max : globalMax;
		globalMin=globalMin > event.min ? event.min : globalMin;
	});
	globalMax=globalMax < 0 ? 0 : globalMax;
	globalMin=globalMin > 0 ? 0 : globalMin;
	console.log (globalMin+" "+globalMax);

	subscriptions.order.forEach(orderStr =>{
		const splitStr=orderStr.split('_');
		const deviceId=splitStr[1];
		const attr=splitStr[2];
		const event=graphData[deviceId][attr];
		var max_=event.max;
		var min_=event.min;
		var cur_=parseFloat(event.current);

		var L=parseFloat(globalMin);
		var H=parseFloat(globalMax);
		var Mi=min_;
		var Ma=max_;
		var C1=cur_ - (0.5*(( options.graphHigh - options.graphLow ) * 0.01)); //the bar is 1% high
		var C2=cur_ + (0.5*(( options.graphHigh - options.graphLow ) * 0.01)); //the bar is 1% highglobalMa

		var na, nb, nc, nd, ne;
		var a, b, c, d, e;

		//Handle all the positive ranges
		a=Mi - L;
		b=C1 - Mi;
		c=C2 - C1;
		d=Ma - C2;
		e=H - Ma;

		//Handle all the negative ranges
		na=-e;
		nb=-d;
		nc=-c;
		nd=-b;
		ne=-a;

		if(H <= 0){
			a=0; b=0; c=0; d=0; e=0;
		} else if (Ma <= 0){
			a=0; b=0; c=0; d=0;
			e=H;
			na=Ma;
		} else if (C2 <=0 ){
			a=0; b=0; c=0;
			d=Ma;
			nb=C2;
			na=0;
		} else if (C1 <= 0){
			a=0; b=0;
			c=C2;
			nc=C1;
			na=0; nb=0;
		} else if (Mi <= 0){
			a=0;
			b=C1;
			nd=Mi;
			na=0; nb=0; nc=0;
		} else if (L <= 0){
			a=Mi;
			ne=L;
			na=0; nb=0; nc=0; nd=0;
		} else{
			na=0; nb=0; nc=0; nd=0; ne=0;
		}

		var cur_String='';
		var units_=``;

		const name=subscriptions.labels[deviceId][attr].replace('%deviceName%', subscriptions.sensors[deviceId].displayName).replace('%attributeName%', attr);
		const colors=subscriptions.colors[deviceId][attr];
		if(colors.annotation_units != null){
			units_=`\${colors.annotation_units}`
		}
		cur_String=``;
		ncur_String=``;
		if(colors.showAnnotation == true){
			if(cur_ >= 0) cur_String=`\${cur_.toFixed(1)}\${units_}`;
			if(cur_ < 0) ncurString=`\${cur_.toFixed(1)}\${units_}`;
		}

		var stats_=`\${name}\nMin: \${min_}\${units_}\nMax: \${max_}\${units_}\nCurrent: \${cur_}\${units_}`

		dataTable.addRow([name, na,	`color: \${colors.backgroundColor}`,																									`\${stats_}`,	'',
								nb,	`color: \${colors.minMaxColor}`,																										`\${stats_}`,	'',
								nc,	`{color: \${colors.currentValueColor}; stroke-color: \${colors.currentValueBorderColor}; stroke-opacity: 1.0; stroke-width: 1;}`,	`\${stats_}`,	ncur_String,
								nd,	`color: \${colors.minMaxColor}`,																										`\${stats_}`,	'',
								ne,	`color: \${colors.backgroundColor}`,																									`\${stats_}`,	'',
								a,		`color: \${colors.backgroundColor}`,																									`\${stats_}`,	'',
								b,		`color: \${colors.minMaxColor}`,																										`\${stats_}`,	'',
								c,		`{color: \${colors.currentValueColor}; stroke-color: \${colors.currentValueBorderColor}; stroke-opacity: 1.0; stroke-width: 1;}`,	`\${stats_}`,	cur_String,
								d,		`color: \${colors.minMaxColor}`,																										`\${stats_}`,	'',
								e,		`color: \${colors.backgroundColor}`,																									`\${stats_}`,	''
		]);

	});

	var chart;

	if(options.graphType == 1){
		chart=new google.visualization.BarChart(document.getElementById("timeline"));
	} else{
		chart=new google.visualization.ColumnChart(document.getElementById("timeline"));
	}
	//if we have a callback
	if(callback) google.visualization.events.addListener(chart, 'ready', callback);

	chart.draw(dataTable, options.graphOptions);
}

google.charts.setOnLoadCallback(onLoad);
window.onBeforeUnload=onBeforeUnload;
		</script>
	</head>
	<body style="${fullSizeStyle}">
		<div id="timeline" style="${fullSizeStyle}" align="center"></div>
	</body>
</html>
	"""

	return html
}

//oauth endpoints

Map getSubscriptions_rangebar(){
	List _ids=[]
	Map _attributes=[:]
	Map labels=[:]
	Map colors=[:]

	Boolean isPoll
	isPoll=(Boolean)state.hasFuel

//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)
			String sa="${sid}_${attribute}".toString()

			_ids << sid // sensor.id
			_attributes[sid]=[]
			labels[sid]=[:]
			colors[sid]=[:]

			_attributes[sid] << attribute
			labels[sid][attribute]=settings["graph_name_override_${sa}"]
			colors[sid][attribute]=["backgroundColor":		settings["attribute_${sa}_background_color_transparent"] ? "transparent" : settings["attribute_${sa}_background_color"],
									"minMaxColor":			settings["attribute_${sa}_minmax_color_transparent"] ? "transparent" : settings["attribute_${sa}_minmax_color"],
									"currentValueColor":	settings["attribute_${sa}_current_color_transparent"] ? "transparent" : settings["attribute_${sa}_current_color"],
									"currentValueBorderColor": settings["attribute_${sa}_current_border_color_transparent"] ? "transparent" : settings["attribute_${sa}_current_border_color"],
									"showAnnotation":			settings["attribute_${sa}_show_value"],
									"annotation_font":		settings["attribute_${sa}_annotation_font"],
									"annotation_units":		settings["attribute_${sa}_annotation_units"],
			]
		}
	}

	Map sensors_fmt=gtSensorFmt()

	List order=graph_order ? parseJson(graph_order) : []

	Map subscriptions=[
			(sID): isPoll ? 'poll' : 'sensor',
			"sensors": sensors_fmt,
			"ids": _ids,
			"attributes": _attributes,
			"labels": labels,
			"colors": colors,
			"order": order
	]

	return subscriptions
}








/*
 * TODO: Radar methods
 */


def tileRadar(){

	List<Map> zoomEnum =	[[3:"3"], [4: "4"], [5: "5"], [6: "6"], [7: "7"], [8: "8"], [9: "9"], [10: "10"]]
	List<Map> refreshEnum=[[60000:"1 minute"], [300000: "5 minutes"], [600000: "10 minutes"], [1200000: "20 minutes"], [1800000: "30 minutes"], [3600000: "1 hour"]]

	List<Map> weatherMapEnum=[["radar" :	"Current Radar"],
								["temp" :	"Temperature"],
								["wind" :	"Wind"],
								["rain" :	"Rain and Thunder"],
								["rainAccu" : "Rain Accumulation"],
								["snowAccu" : "Snow Accumulation"],
								["snowcover": "Snow Ground Cover"]]

	List<Map> forecastModelEnum =[["ecmwf":	"European Centre for Medium-Range Weather Forecasts"],
								["gfs":	"Global Forecast System"]]

	List<Map> hoursModelEnum=[["now" : "Current"],
								["12" : "12 Hours"],
								["24" : "24 Hours"]]

	List<Map> measureEnum=[["in": "inches"],
							["mm": "millimeters"]]

	List<Map> windEnum=[["knot" : "Knots (k)"],
						["meters_per_second" : "Meters / Second (m/s)"],
						["kilometers_per_hour" : "Kilometers / Hour (km/h)"],
						["miles_per_hour" : "Miles per Hour (mph)"]]

	List<Map> tempEnum =	[[(sFAHR): "Fahrenheit (°F)"],
							 [(sCELS) : "Celsius (°C)"]]

	dynamicPage(name: "graphSetupPage"){

		List container

		hubiForm_section("Tile Setup", 1, sBLK, sBLK){
			container=[]
			container << hubiForm_text_input ("<b>Latitude (Default=Hub location)</b>", "latitude", location.latitude.toString(), false)
			container << hubiForm_text_input ("<b>Longitude (Default=Hub location)</b>", "longitude", location.longitude.toString(), false)

			hubiForm_container(container, 1)

			//if(!overlay) overlay="radar";
/*			if(!settings.overlay){
				app.updateSetting("overlay", [(sTYPE): sENUM, (sVAL): "radar"])
				settings['overlay']='radar'
				app.updateSetting("refresh", [(sTYPE): sENUM, (sVAL): 600000])
				settings['refresh']=600000
				app.updateSetting("zoom", [(sTYPE): sENUM, (sVAL): 3])
				settings['zoom']=3
			} */

			input( (sTYPE): sENUM, (sNM): "zoom",(sTIT): "<b>Zoom Amount</b>", required: false, multiple: false, options: zoomEnum, defaultValue: 3, submitOnChange: false)
			input( (sTYPE): sENUM, (sNM): "refresh",(sTIT): "<b>Refresh Time</b>", required: false, multiple: false, options: refreshEnum, defaultValue: 600000, submitOnChange: false)
			input( (sTYPE): sENUM, (sNM): "overlay",(sTIT): "<b>Map Type</b>", required: false, multiple: false, options: weatherMapEnum, defaultValue: "radar", submitOnChange: true)

			if(settings.overlay != "radar"){
				container=[]
				container << hubiForm_text("""<b>You have chosen a forecast map.</b> Please note:<br>
									1. Forecast maps are update on the hour<br>
									2. "Current" is the current condition (within the last hour)<br>
									3. Refreshing these maps "more often" won't change anything""")
				hubiForm_container(container, 1)

				if(product == "radar") app.updateSetting("product", [(sTYPE): sENUM, (sVAL): "gfs"])
				input( (sTYPE): sENUM, (sNM): "product",(sTIT): "<b>Forecast Model</b>", required: false, multiple: false, options: forecastModelEnum, defaultValue: "gfs", submitOnChange: false)
				input( (sTYPE): sENUM, (sNM): "calendar",(sTIT): "<b>Display Time</b>", required: false, multiple: false, options: hoursModelEnum, defaultValue: "now", submitOnChange: false)
			}else{
				app.updateSetting ("product", [(sTYPE): sENUM, (sVAL): "gfs"])
				app.updateSetting ("calendar", [(sTYPE): sENUM, (sVAL): "now"])
			}

/*			if(!settings.wind_units){
				app.updateSetting("wind_units", [(sTYPE): sENUM, (sVAL): "miles_per_hour"])
				settings['wind_units']='miles_per_hour'
				app.updateSetting("temp_units", [(sTYPE): sENUM, (sVAL): sFAHR])
				settings['temp_units']='fahrenheit'
				app.updateSetting("background", [(sTYPE): sENUM, (sVAL): "#000000"])
				settings['background']='#000000'
				app.updateSetting("background_opacity", [(sTYPE): sENUM, (sVAL): 90])
				settings['background_opacity']=90
			} */
			input( (sTYPE): sENUM, (sNM): "wind_units",(sTIT): "<b>Wind Speed Units</b>", required: false, multiple: false, options: windEnum, defaultValue: "miles_per_hour", submitOnChange: false)
			input( (sTYPE): sENUM, (sNM): "temp_units",(sTIT): "<b>Temperature Units</b>", required: false, multiple: false, options: tempEnum, defaultValue: sFAHR, submitOnChange: false)
			container=[]
			container << hubiForm_switch((sTIT): "<b>Show Marker on Graph?</b>",
					(sNM): "marker",
					default: false,
					submit_on_change: false)

			container << hubiForm_color("Background",
					"background",
					"#000000",
					false)

			container << hubiForm_slider	((sTIT): "Background Opacity",
					(sNM): "background_opacity",
					default: 90,
					min: 0,
					max: 100,
					units: "%",
					submit_on_change: false)
			hubiForm_container(container, 1)

		}
	}
}


def mainRadar(){
	dynamicPage(name: "mainPage"){

		checkDup()
		List container
		if(!state.endpoint){
			hubiForm_section("Please set up OAuth API", 1, "report", sBLK){
				href( (sNM): "enableAPIPageLink",(sTIT): "Enable API", description: sBLK, page: "enableAPIPage")
			}
		}else{
			hubiForm_section(tDesc()+" Graph Options", 1, "tune", sBLK){
				container=[]
				container << hubiForm_page_button("Setup Tile", "graphSetupPage", "100%", "vibration")
				hubiForm_container(container, 1)
			}

			if(settings.wind_units){
				local_graph_url()
				preview_tile()
			}

			put_settings()

		}
	}
}


String getGraph_radar(){

	//String fullSizeStyle="margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden"

	String wind
	wind="kt"
	switch (wind_units){
		case "knot" : wind="kt"; break
		case "meters_per_second" : wind="m%2Fs"; break
		case "kilometers_per_hour" : wind="km%2Fh"; break
		case "miles_per_hour" : wind="mph"; break
	}

	String temp
	temp="%C2%B0F"
	switch (temp_units){
		case sFAHR: temp="%C2%B0F"; break
		case sCELS : temp="%C2%B0C"
	}
	String html="""

<style>
.wrapper{
	display: flex;
	flex-flow: column;
	height: 100%;
	background-color: ${getRGBA(background_color, background_opacity)};
}

</style>

<div class="wrapper" id="radar">
	<iframe id="windy2" style="position: absolute !important;z-index: 2;" src="" data-fs="false">
	</iframe>

	<iframe id="windy" style="position: absolute !important; z-index: 3;" src="" onload="(() =>{
		const NAME='once';
		var frameRefreshInterval;
		var count=0;

		if(this.name !== NAME){
			console.log('START')
			this.name=NAME
			frameRefreshInterval=setInterval(refreshFrame, ${refresh});
		}

		function refreshFrame(){
			console.log('Refresh'+count);

			document.getElementById('windy2').style.visibility='visible';
			//this.style.visibilty='hidden';
			document.getElementById('windy').style.zIndex=1;
			document.getElementById('windy').src=document.getElementById('windy').src
			count++;
		}

		setTimeout(() =>{ document.getElementById('windy').style.zIndex=3; }, 1000);
		setTimeout(() =>{ document.getElementById('windy2').src=document.getElementById('windy2').src }, 2000);

	})()"></iframe>
</div>

<script>

var url="https://embed.windy.com/embed2.html";
var width=document.getElementById('radar').offsetWidth-5;
var height=window.innerHeight-15;

var params="?lat=${latitude}&lon=${longitude}&detailLat=${latitude}&detailLon=${longitude}&width="+width+"&height"+height+"&zoom=${zoom}&level=surface&overlay=${settings.overlay}&product=${product}&menu=&message=true&marker=${marker==true ? 'true' : ''}&calendar=${calendar}&pressure=&type=map&location=coordinates&detail=&metricWind=${wind}&metricTemp=${temp}&radarRange=-1"

var iframe_url=url + params;

console.log(iframe_url);

document.getElementById("windy").src=iframe_url;
document.getElementById("windy2").src=iframe_url;
document.getElementById("windy").width=width+"px";
document.getElementById("windy2").width=width+"px";
document.getElementById("windy").height=height+"px";
document.getElementById("windy2").height=height+"px";

</script>

"""
	return html
}


//oauth endpoints







/*
 * TODO: Weather2 methods
 */

def tileWeather2(){

	List updateEnum=[["60000":"1 Minute"],["300000":"5 Minutes"], ["600000":"10 Minutes"], ["1200000":"20 Minutes"], ["1800000":"Half Hour"],
					   ["3600000":"1 Hour"], ["6400000":"2 Hours"], ["19200000":"6 Hours"], ["43200000":"12 Hours"], ["86400000":"1 Day"]]

	dynamicPage(name: "graphSetupPage"){

		hubiForm_section("General Options", 1, sBLK, sBLK){
			input( (sTYPE): sENUM, (sNM): "openweather_refresh_rate",(sTIT): "<b>Select OpenWeather Update Rate</b>", multiple: false, required: true, options: updateEnum, defaultValue: "300000")

			List container=[]

			container << hubiForm_color("Background",
					"background",
					"#000000",
					false)
			container << hubiForm_slider	((sTIT): "Background Opacity",
					(sNM): "background_opacity",
					default: 90,
					min: 0,
					max: 100,
					units: "%",
					submit_on_change: false)

			container << hubiForm_switch	((sTIT): "Color Icons?", (sNM): "color_icons", default: false)

			hubiForm_container(container, 1)

//			List<Map> daysEnum=[[0: "Today"], [1: "Tomorrow"], [2: "2 Days from Now"], [3: "3 Days from Now"], [4: "4 Days from Now"], [5: "Five Days from Now"]]
//			input( (sTYPE): sENUM, (sNM): "day_num",(sTIT): "Day to Display", multiple: false, required: false, options: daysEnum, defaultValue: "1")
		}

		//List decimalEnum =	[[0: "None (0)"], [1: "One (0.1)"], [2: "Two (0.12)"], [3: "Three (0.123)"], [4: "Four (0.1234)"]]
		((Map<String,Map>)state.unit_type).each{String key, Map measurement->
			if(measurement.out != sNONE ){
				hubiForm_section(sMs(measurement,sNM), 1, sBLK, sBLK){
					List container=[]
					hubiForm_container(container, 1)
					input( (sTYPE): sENUM, (sNM): key+"_units",(sTIT): "Displayed Units", required: false, multiple: false,
							options: measurement.enum, defaultValue: measurement.out, submitOnChange: false)
				}
			}
		}
	}
}


def deviceWeather2(){
	List<Map> final_attrs

	dynamicPage(name: "deviceSelectionPage"){
		List container
		hubiForm_section("Device Selection", 1, sBLK, sBLK){
			container=[]
			container << hubiForm_switch((sTIT): "Make Hubitat Devices Available?", (sNM): "override_openweather", default: false, submit_on_change: true)
			hubiForm_container(container, 1)
		}

		Map<String,Map<String,Map>> measurement_list=[:]
		if(override_openweather == true){
			hubiForm_section("Sensor Selection", 1, sBLK, sBLK){
				container=[]
				if(container)hubiForm_container(container, 1)
				input ("sensors", "capability.*",(sTIT): "Select Sensors", multiple: true, required: false, submitOnChange: true)
			}
			if(sensors){
				final_attrs=[]
				Map<String,Map<String,Map>> sensor_list=[:]
				for(sensor in (List)sensors){
					List attributes_=(List)sensor.getSupportedAttributes()
					String sid=gtSensorId(sensor)
					sensor_list."${sid}"=[:]
					for(attribute_ in attributes_){
						String name=attribute_.getName()
						def cv= sensor.currentState(name,true)
						if(cv){
							String units=cv.getUnit()
							def value=cv.getValue()
							String dn=sensor.displayName
							sensor_list."${sid}"."${name}"=[ sensor_name: "${dn}", (sVAL): value, unit: units, supported_unit: getUnits(units, value)]
							final_attrs << [("${sid}.${name}".toString()) : "${dn} (${name}) ::: [${value} ${units ?: ""} ]"]
						}
					}
				}
				final_attrs=final_attrs.unique(false)

				((Map<String,Map>)state.unit_type).each{String key, Map type->
					measurement_list."${key}"=[:]
					if(type.out != sNONE){
						hubiForm_section(sMs(type,sNM), 1, sBLK, sBLK){
							container=[]
							input( (sTYPE): sENUM, (sNM): "${key}_devices",(sTIT): sMs(type,sNM), required: false, multiple: true, options: final_attrs, defaultValue: sBLK, submitOnChange: true)
							if(settings["${key}_devices"]){
								settings["${key}_devices"].each{ String iattr->
									String attr
									attr=iattr
									String sensor_id="${attr}".tokenize('.')[0]
									if(!measurement_list."${key}"."${sensor_id}")
										measurement_list."${key}"."${sensor_id}"=[:]

									attr=attr.tokenize('.')[1]
									String sensor_name=sensor_list."${sensor_id}"."${attr}".sensor_name

									if(((Map<String,Map>)state.unit_type)."${key}".enum == sNONE){
										container << hubiForm_text("<b>"+sensor_name+" :: "+attr+"</b>")
										measurement_list."${key}"."${sensor_id}"."${attr}"=[sensor_name: sensor_list."${sensor_id}"."${attr}".sensor_name,
																							in_units: sNONE
										]

									}else if(sensor_list."${sensor_id}"."${attr}".supported_unit.var == key){
										String units=sensor_list."${sensor_id}"."${attr}".supported_unit.name
										container << hubiForm_text("<b>"+sensor_name+" :: "+attr+"</b></br>"+'&#9;'+" Units="+units)
										measurement_list."${key}"."${sensor_id}"."${attr}"=[sensor_name: sensor_list."${sensor_id}"."${attr}".sensor_name,
																							in_units: sensor_list."${sensor_id}"."${attr}".supported_unit.units
										]

									}else{
										if(container)hubiForm_container(container, 1)

										String unit=sensor_list."${sensor_id}"."${attr}".unit
										List<Map> list=((Map<String,Map>)state.unit_type)."${key}".enum
										if(list[0].none != "None")
											input( (sTYPE): sENUM, (sNM): "${key}.${sensor_id}.${attr}",
													(sTIT): "<b>"+sensor_name+" :: "+attr+"</b><br>Valid units not detected ("+unit+'); Expected <b>"'+key+'"</b> type<br><small>Please select measurement units below</small>',
													required: false, multiple: false,
													options: list,
													defaultValue: sBLK, submitOnChange: false)

										measurement_list."${key}"."${sensor_id}"."${attr}"=[sensor_name: sensor_list."${sensor_id}"."${attr}".sensor_name,
																							in_units: settings["${key}.${sensor_id}.${attr}"]
										]

										container=[]
									}
								}
							}
							if(container)hubiForm_container(container, 1)
						}
					}
				}
			}
			state.device_list=measurement_list
		}else{
			//TODO clear out unused settings, sensors
			wremoveSetting('sensors')
		}
	}
}

@Field static final String sFAHR='fahrenheit'
@Field static final String sCELS='celsius'
@Field static final String sNONE='none'
@Field static final String sYES='yes'
@Field static final String sNO='no'
@Field static final String sTEMP='temperature'
@Field static final String sCUR='current'

@Field List unitTemp =		[[(sFAHR): "Fahrenheit (°F)"], [(sCELS) : "Celsius (°C)"], ["kelvin" : "Kelvin (K)"]]
@Field List unitWind =		[["meters_per_second": "Meters per Second (m/s)"], ["miles_per_hour": "Miles per Hour (mph)"], ["knots": "Knots (kn)"], ["kilometers_per_hour": "Kilometers per Hour (km/h)"]]
@Field List unitDepth =		[["millimeters": "Millimeters (mm)"], ["inches": """Inches (") """]]
@Field List unitPressure=	[["millibars": "Millibars (mbar)"], ["millimeters_mercury": "Millimeters of Mercury (mmHg)"], ["inches_mercury": "Inches of Mercury (inHg)"], ["hectopascal" : "Hectopascal (hPa)"]]
@Field List unitDirection=	[["degrees": "Degrees (°)"], ["radians" : "Radians (°)"], ["cardinal": "Cardinal (N, NE, E, SE, etc)"]]
@Field List unitTrend =		[["trend_numeric": "Numeric (° < 0, °=0, ° > 0)"], ["trend_text": "Text (° rising, ° steady, ° falling)"]]
@Field List unitPercent =	[["percent_numeric": "Numeric (0 to 100)"], ["percent_decimal": "Decimal (0.0 to 1.0)"]]
@Field List unitTime =		[["time_seconds" : "Seconds since 1970"], ["time_milliseconds" : "Milliseconds since 1970"], ["time_twelve" : "12 Hour (2:30 PM)"], ["time_two_four" : "24 Hour (14:30)"]]
@Field List unitUVI=		[["uvi" : "UV Index"]]
@Field List unitDistance=	[["miles": "Miles"]]
@Field List unitBlank=		[[(sNONE): "None"]]
@Field List unitDayofWeek=	[["short": "Short (Thu)"], ["long": "Long (Thursday)"]]
@Field List unitText=		[["plain": "Unformatted"], ["title": "Title Format"], ["lowercase": "Lowercase"], ["uppercase" : "Uppercase"]]
@Field List unitIcon=		[[(sICON): "Default Icon"]]

@Field List<Map<String,Object>> tileSetFLD= [
		[
				(sTIT): 'Forecast Weather Icon',		var: "weather_icon", (sTYPE): "weather_icon", period:sCUR, (sVAL): sBLK,
				(sICON): "alert-circle", icon_loc: "center", icon_space: sBLK,
				h: 6, w: 12, baseline_row: 1, baseline_column: 13,
				alignment: "center", text: sBLK, decimals: 1,
				lpad: 0, rpad: 0,
				unit: sNONE, decimal: sNO, unit_space: sBLK,
				font: 40, font_weight: "100",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Current Weather',		var: "description", (sTYPE): "weather_description", period:sCUR, (sVAL): 0,
				(sICON): sNONE, icon_loc: sNONE, icon_space: sBLK,
				h: 4, w: 12, baseline_row: 7, baseline_column: 13,
				alignment: "center", text: sBLK, decimals: 0,
				lpad: 0, rpad: 0,
				unit: sNONE, decimal: sNO, unit_space: sBLK,
				font: 20, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Current Temperature',		var: "current_temperature", (sTYPE): sTEMP, period:sCUR,
				(sICON): sNONE, icon_loc: "left", icon_space: sBLK,
				h: 4, w: 12, baseline_row: 1, baseline_column: 1,
				alignment: "center", text: sBLK, decimals: 1,
				lpad: 0, rpad: 0,
				unit: unitTemp, decimal: sYES, unit_space: sBLK,
				font: 20, font_weight: "900",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Feels Like',				var: "feels_like", (sTYPE): "feels_like", period:sCUR,
				(sICON): "home-thermometer-outline", icon_loc: "left", icon_space: sSPC,
				h: 2, w: 12, baseline_row: 5, baseline_column: 1,
				alignment: "center", text: "Feels Like: ", decimals: 1,
				lpad: 0, rpad: 0,
				unit: unitTemp, decimal: sYES, unit_space: sBLK,
				font: 7, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Forecast High',				var: "forecast_high", (sTYPE): "temperature_max", period:"daily.0",
				(sICON): "arrow-up-thick", icon_loc: "left", icon_space: sBLK,
				h: 4, w: 6, baseline_row: 7, baseline_column: 7,
				alignment: "center", text: sBLK, decimals: 1,
				lpad: 0, rpad: 0,
				unit: unitTemp, decimal: sYES, unit_space: sBLK,
				font: 7, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Forecast Low',				var: "forecast_low", (sTYPE): "temperature_min", period:"daily.0",
				(sICON): "arrow-down-thick", icon_loc: "left",  icon_space: sBLK,
				h: 4,  w: 6, baseline_row: 7,  baseline_column:  1,
				alignment: "center", text: sBLK,  decimals: 1,
				lpad: 0, rpad: 0,
				unit: unitTemp,   decimal: sYES, unit_space: sBLK,
				font: 6, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Precipitation Title',		var: "precipitation_title", (sTYPE): "blank",  period:sNONE,
				(sICON): "umbrella-outline", icon_loc: "left",  icon_space: sSPC,
				h: 2,  w: 8, baseline_row: 11,  baseline_column:  1,
				alignment: "center", text: "Precipitation",
				lpad: 0, rpad: 0,  decimals: 1,
				unit: unitDepth,   decimal: sNO,  unit_space: sBLK,
				font: 6, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Forecast Precipitation',	var: "forecast_precipitation", (sTYPE): "rain", period:"daily.0",
				(sICON): "ruler", icon_loc: "left",  icon_space: sSPC,
				h: 2,  w: 8, baseline_row: 15,  baseline_column:  1,
				alignment: "center", text: sBLK,
				lpad: 0, rpad: 0,  decimals: 1,
				unit: unitDepth,   decimal: sYES, unit_space: sBLK,
				font: 4, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,

		],
		[
				(sTIT): 'Forecast Percent Precipitation', var: "forecast_percent_precipitation", (sTYPE): "chance_precipitation", period:"daily.0",
				(sICON): "cloud-question", icon_loc: "left",  icon_space: sSPC,
				h: 2,  w: 8, baseline_row: 13,  baseline_column: 1,
				alignment: "center", text: sBLK,  decimals: 1,
				lpad: 0, rpad: 0,
				unit: unitPercent,   decimal: sYES, unit_space: sBLK,
				font: 4, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Current Precipitation',		var: "current_precipitation", (sTYPE): "rain_past_hour", period:sCUR,
				(sICON): "calendar-today", icon_loc: "left",  icon_space: sSPC,
				h: 2,  w: 8, baseline_row: 17,  baseline_column:  1,
				alignment: "center", text: sBLK,  decimals: 1,
				lpad: 0, rpad: 0,
				unit: unitDepth,   decimal: sYES,  unit_space: sBLK,
				font: 4, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Wind Title',			var: "wind_title", (sTYPE): "blank",   period:sNONE,
				(sICON): "weather-windy-variant", icon_loc: "left",  icon_space: sSPC,
				h: 2,  w: 8, baseline_row: 11,  baseline_column:  9,
				alignment: "center", text: "Wind",  decimals: 1,
				lpad: 0, rpad: 0,
				unit: sNONE,   decimal: sNO, unit_space: sBLK,
				font: 4, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Wind Speed',			var: "wind_speed", (sTYPE): "wind_speed",  period:sCUR,
				(sICON): "tailwind", icon_loc: "left",  icon_space: sSPC,
				h: 2,  w: 8, baseline_row: 13,  baseline_column:  9,
				alignment: "center", text: sBLK,  decimals: 1,
				lpad: 0, rpad: 0,
				unit: unitWind,   decimal: sYES, unit_space: sSPC,
				font: 4, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Wind Gust',				var: "wind_gust", (sTYPE): "wind_gust",  period:sCUR,
				(sICON): "weather-windy", icon_loc: "left",  icon_space: sSPC,
				h: 2,  w: 8, baseline_row: 15,  baseline_column:  9,
				alignment: "center", text: sBLK,  decimals: 1,
				lpad: 0, rpad: 0,
				unit: unitWind,   decimal: sYES, unit_space: sSPC,
				font: 4, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Wind Direction',		var: "wind_direction", (sTYPE): "wind_direction",  period:sCUR,
				(sICON): "compass-outline", icon_loc: "left",  icon_space: sSPC,
				h: 2,  w: 8, baseline_row: 17,  baseline_column:  9,
				alignment: "center", text: sBLK,  decimals: 1,
				lpad: 0, rpad: 0,
				unit: unitDirection,   decimal: sNO, unit_space: sBLK,
				font: 4, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Pressure Title',		var: "pressure_title", (sTYPE): "blank", period:sCUR,
				(sICON): "gauge", icon_loc: "left",  icon_space: sSPC,
				h: 2,  w: 8, baseline_row: 11,  baseline_column:  17,
				alignment: "center", text: "Pressure", decimals: 1,
				lpad: 0, rpad: 0,
				unit: sNONE,   decimal: sYES, unit_space: sBLK,
				font: 4, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Current Pressure',		var: "current_pressure", (sTYPE): "pressure", period:sCUR,
				(sICON): "thermostat", icon_loc: "left",  icon_space: sSPC,
				h: 2,  w: 8, baseline_row: 13,  baseline_column:  17,
				alignment: "center", text: sBLK,  decimals: 1,
				lpad: 0, rpad: 0,
				unit: unitPressure,   decimal: sYES,  unit_space: sSPC,
				font: 4, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Humidity',				var: "current_humidity", (sTYPE): "humidity", period:sCUR,
				(sICON): "water-percent", icon_loc: "left",  icon_space: sSPC,
				h: 2,  w: 4, baseline_row: 20,  baseline_column:  1,
				alignment: "center", text: sBLK,  decimals: 1,
				lpad: 0, rpad: 0,
				unit: unitPercent,   decimal: sYES, unit_space: sBLK,
				font: 4, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Current Dewpoint',		var: "current_dewpoint", (sTYPE): "dew_point", period:sCUR,
				(sICON): "wave", icon_loc: "left",  icon_space: sSPC,
				h: 2,  w: 4, baseline_row: 20,  baseline_column: 11,
				alignment: "center", text: sBLK,  decimals: 1,
				lpad: 0, rpad: 0,
				unit: unitTemp,   decimal: sYES, unit_space: sBLK,
				font: 4, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Sunrise',				var: "sunrise", (sTYPE): "sunrise",  period:sCUR,
				(sICON): "weather-sunset-up", icon_loc: "left",  icon_space: sSPC,
				h: 2,  w: 5, baseline_row: 20,  baseline_column:  15,
				alignment: "center", text: sBLK,  decimals: 1,
				lpad: 0, rpad: 0,
				unit: unitTime,   decimal: sNO,  unit_space: sBLK,
				font: 3, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
		[
				(sTIT): 'Sunset',				var: "sunset", (sTYPE): "sunset",  period:sCUR,
				(sICON): "weather-sunset-down", icon_loc: "left",  icon_space: sSPC,
				h: 2,  w: 5, baseline_row: 20,  baseline_column:  20,
				alignment: "center", text: sBLK,  decimals: 1,
				lpad: 0, rpad: 0,
				unit: unitTime,   decimal: sNO,  unit_space: sBLK,
				font: 3, font_weight: "400",
				font_color: "#2c3e50", font_opacity: "100", background_color: "#18bc9c", background_opacity: "100",
				font_auto_resize: "true", justification: "center", font_adjustment: 0, display: true,
		],
]

@Field Map<String,Map> spanFLD= [
		current: [(sTIT): "Current Measurements", num_time: 0, time_units:  sBLK],
		daily:   [(sTIT): "Daily Forecast", num_time: 7, time_units:  "day"],
		hourly:  [(sTIT): "Hourly Forecast", num_time: 48, time_units: "hour"],
		blank:   [(sTIT): "Blank Tile", num_time: 0, time_units:  sBLK],
		sensor:  [(sTIT): "Device Measurement", num_time: 0, time_units: sBLK],
]

private static Map fill_temp_type(String name, String type, String ow, String in_units, String current, String hourly, String daily, String sensor){
	return [(sNM): name, (sTYPE): type, ow: ow, in_units: in_units,current: current, hourly: hourly, daily: daily, sensor: sensor]
}

//@Field static Map<String,Map> span_typeFLD

def mainWeather2(){

//	state.tile_dimensions=[rows: 14, columns: 26]
	state.remove('span_type')
	state.remove('tile_dimensions')

	// one time initialization
	if(!state.tile_settings){

/*		Map<String,Map> tmap
		spanFLD.each{ String key, Map item ->
			if(!tmap) tmap=[:]
			tmap += [(key): [:]+item]
		}
		span_typeFLD= tmap */
/*		state.span_type=[ current: [(sTIT): "Current Measurements", num_time: 0, time_units:  ""],
							daily:   [(sTIT): "Daily Forecast", num_time: 7, time_units:  "day"],
							hourly:  [(sTIT): "Hourly Forecast", num_time: 48, time_units: "hour"],
							blank:   [(sTIT): "Blank Tile", num_time: 0, time_units:  ""],
							sensor:  [(sTIT): "Device Measurement", num_time: 0, time_units: ""],
		] */

		List<Map> list=[]
		tileSetFLD.each{Map<String,Object> item->
			Map<String,Object> tmap1
			tmap1=[:]+item
/*			item.each{ String key, item1 ->
				tmap1 += [(key): item1]
			} */
			list << tmap1
		}
		// This is the internal DB of current values and settings adjustments
		state.tile_settings=list

	} //else{

	// this remaps internal variables to the source types - can change based on settings/overrides
	Map<String,Map> temp_type=[
		weather_icon:			fill_temp_type("Weather Icon",sICON,"weather.0.description",sNONE,sYES,sYES,sYES,sNO),
		weather_description:	fill_temp_type("Weather Description", sTEXT,"weather.0.description", sNONE,sYES,sYES,sYES,sNO),

		feels_like:				fill_temp_type("Feels Like", sTEMP,"feels_like",sFAHR, sYES,sYES,sNO,sNO),
		feels_like_morning:		fill_temp_type("Morning Feels Like", sTEMP, "feels_like.morn", sFAHR, sNO, sNO, sYES, sNO),
		feels_like_day:			fill_temp_type("Day Feels Like", sTEMP, "feels_like.day", sFAHR, sNO, sNO, sYES, sNO),
		feels_like_evening:		fill_temp_type("Evening Feels Like", sTEMP, "feels_like.eve", sFAHR, sNO, sNO, sYES, sNO),
		feels_like_night:		fill_temp_type("Night Feels Like", sTEMP, "feels_like.night", sFAHR, sNO, sNO, sYES, sNO),

		temperature:			fill_temp_type("Temperature", sTEMP, "temp", sFAHR, sYES, sYES, sNO, sNO),
		temperature_max:		fill_temp_type("Maximum Temperature", sTEMP, "temp.max", sFAHR, sNO, sNO, sYES, sNO),
		temperature_min:		fill_temp_type("Minimum Temperature", sTEMP, "temp.min", sFAHR, sNO, sNO, sYES, sNO),
		temperature_morning:	fill_temp_type("Morning Temperature", sTEMP, "temp.morn", sFAHR, sNO, sNO, sYES, sNO),
		temperature_day:		fill_temp_type("Day Temperature", sTEMP, "temp.day", sFAHR, sNO, sNO, sYES, sNO),
		temperature_evening:	fill_temp_type("Evening Temperature", sTEMP, "temp.eve", sFAHR, sNO, sNO, sYES, sNO),
		temperature_night:		fill_temp_type("Night Temperature", sTEMP, "temp.night", sFAHR, sNO, sNO, sYES, sNO),

		humidity:				fill_temp_type("Humidity", "percent", "humidity", "percent_numeric", sYES, sYES, sYES, sNO),

		dew_point:				fill_temp_type("Dew Point",sTEMP, "dew_point", sFAHR, sYES, sYES, sYES, sNO),

		pressure:				fill_temp_type("Pressure","pressure", "pressure", "millibars", sYES, sYES, sYES, sNO),

		uv_index:				fill_temp_type("UV Index", "uvi", "uvi", "uvi", sYES, sNO, sYES, sNO),
		cloud_coverage:			fill_temp_type("Cloud Coverage", "percent", "clouds", "percent_numeric", sYES, sNO, sYES, sNO),
		visibility:				fill_temp_type("Visibility", "distance", "visibility", "miles", sYES, sNO, sYES, sNO),

		wind_speed:				fill_temp_type("Wind Speed", "velocity", "wind_speed", "miles_per_hour",sYES, sYES, sYES, sNO),
		wind_gust:				fill_temp_type("Wind Gust", "velocity", "wind_gust", "miles_per_hour", sYES, sYES, sYES, sNO),
		wind_direction:			fill_temp_type("Wind Direction", "direction", "wind_deg", "degrees", sYES, sYES, sYES, sNO),

		rain_past_hour:			fill_temp_type("Rain past Hour", "depth", "rain.1h", "millimeters", sYES, sYES, sNO, sNO),
		snow_past_hour:			fill_temp_type("Snow past Hour", "depth", "snow.1h", "millimeters", sYES, sYES, sNO, sNO),
		rain:					fill_temp_type("Rain", "depth", "rain", "millimeters", sNO, sNO, sYES, sNO),
		snow:					fill_temp_type("Snow", "depth", "snow", "millimeters", sNO, sNO, sYES, sNO),
		precipitation:			fill_temp_type("Precipitation", "depth", "precipitation", "millimeters", sNO, sNO, sYES, sNO),
		chance_precipitation:	fill_temp_type("Chance of Precipitation", "percent", "pop", "percent_decimal", sYES, sYES, sYES, sNO),

		sunrise:				fill_temp_type("Sunrise", "time", "sunrise", "time_seconds", sYES, sYES, sYES, sNO),
		sunset:					fill_temp_type("Sunset", "time", "sunset", "time_seconds", sYES, sYES, sYES, sNO),

		hour:					fill_temp_type("Hour", "time", "dt", "time_seconds", sNO, sYES, sNO, sNO),
		day:					fill_temp_type("Day", "day", "dt", "time_seconds", sNO, sYES, sYES, sNO),

		blank:					fill_temp_type("Blank Tile", "blank", sNONE, sNONE, sNO, sNO, sNO, sNO),
		time_stamp:				fill_temp_type("Data Time Stamp", "time", "dt", "time_seconds", sYES, sNO, sNO, sNO)
	]

	//atomicState.tile_type=temp_type

	Map<String,Map> temp_unit=[
		temperature:		[(sNM): "Temperature",		(sENUM): unitTemp,		out:  sFAHR,			parse_func: "formatNumericData"],
		percent:			[(sNM): "Percentage",		(sENUM): unitPercent,	out:  "percent_numeric", parse_func: "formatNumericData"],
		(sICON):			[(sNM): "Weather Icons",	(sENUM): unitIcon,		out:  sNONE,			parse_func: "translateCondition"],
		pressure:			[(sNM): "Pressure",			(sENUM): unitPressure,	out:  "inches_mercury", parse_func: "formatNumericData"],
		velocity:			[(sNM): "Velocity",			(sENUM): unitWind,		out:  "miles_per_hour", parse_func: "formatNumericData"],
		time:				[(sNM): "Time",				(sENUM): unitTime,		out:  "time_twelve",	parse_func: "formatNumericData"],
		depth:				[(sNM): "Depth",			(sENUM): unitDepth,		out:  "inches",			parse_func: "formatNumericData"],
		direction:			[(sNM): "Direction",		(sENUM): unitDirection, out:  "cardinal",		parse_func: "formatNumericData"],
		uvi:				[(sNM): "UV Index",			(sENUM): unitUVI,		out:  "uvi",			parse_func: "formatNumericData"],
		visibility:			[(sNM): "Visibility",		(sENUM): unitDistance,	out:  "visibility",		parse_func: "formatNumericData"],
		blank:				[(sNM): "Blank Tile",		(sENUM): unitBlank,		out:  sNONE,			parse_func: sNONE],
		day:				[(sNM): "Day of Week",		(sENUM): unitDayofWeek, out:  "short",			parse_func: "formatDayData"],
		text:				[(sNM): "Text Description",	(sENUM): unitText,		out:  "plain",			parse_func: "formatTextData"],
	]

	//atomicState.unit_type=temp_unit

	//Update the Output Types
	Map<String,Map> unitT= [:]+temp_unit // atomicState.unit_type
	temp_unit.each{String key, Map item->
		if(settings["${key}_units"]){
			unitT."${key}".out=settings["${key}_units"]
		}
	}
	state.unit_type=unitT

	//reset to OpenWeather Data
	Map<String,Map> temp=[:] + temp_type //atomicState.tile_type
	temp.wind_speed.in_units='miles_per_hour'
	temp.wind_gust.in_units='miles_per_hour'
	//atomicState.tile_type.each{key, item->
	temp_type.each{String key, Map item->
		if(sMs(item,'sensor') == sNO){
			temp << [(key): item]
		}
	}
	state.tile_type=temp
//	}

//	Map<String,Map> temp=(Map<String,Map>)state.tile_type
	count=0
	((Map<String,Map<String,Map<String,Map>>>)state.device_list).each{ String type, Map<String,Map<String,Map>>var1->
		if(var1 != [:]){
			var1.each{String device, Map<String,Map> var2->
				var2.each{String attr, Map var3->
					temp."device_${device}_${attr}_${type}"=[(sNM): "${var3.sensor_name} :: ${attr} (${type})", (sTYPE): "${type}", ow: "device.${device}.${attr}", in_units: var3.in_units, current: sNO, hourly: sNO, daily: sNO, sensor: sYES]
				}
			}
		}
	}
	state.tile_type=temp

	TreeMap<String,TreeMap> typeList= new TreeMap([:])
	typeList.main_list=new TreeMap([:])
	spanFLD.each{String span_key, Map span->
		((TreeMap)typeList.main_list).put(span_key, [(sNM): span_key.capitalize()])
		typeList[span_key]= new TreeMap([:])
		((TreeMap)typeList[span_key]).measurement_list=new TreeMap([:])
		((Map<String,Map>)state.tile_type).each{String key, Map item->
			if(item[span_key] == sYES)
				((TreeMap)((TreeMap)typeList[span_key]).measurement_list) << [(key): [(sNM): sMs(item,sNM)]]
		}
		if((Integer)span.num_time > 0){
			((TreeMap)typeList[span_key]).time_list=new TreeMap([:])
			((TreeMap)typeList[span_key]).title=((String)span.time_units).capitalize()+"s to Display"
			Map a
			Integer i
			for(i=0; i<(Integer)span.num_time; i++){
				a=null
				if(i==0){
					if(span.time_units == "day")
						a=["00" : [(sNM): " Today"]]
				}else
				if(span.time_units == "day" && i==1)
					a=["01" : [(sNM): " Tomorrow"]]
				else if(i==1)
					a=["01" : [(sNM): " $i ${span.time_units} from now"]]
				else if(i<10)
					a=[("0$i".toString()) : [(sNM): " $i ${span.time_units}s from now"]]
				else
					a=[("$i".toString()) : [(sNM): " $i ${span.time_units}s from now"]]

				if(a) ((TreeMap)((TreeMap)typeList[span_key]).time_list) << a
			}
		}
	}
	//atomicState.newTileDialog=""
	state.newTileDialog=typeList.sort()

	dynamicPage(name: "mainPage"){

		checkDup()
		List container
		if(!state.endpoint){
			hubiForm_section("Please set up OAuth API", 1, "report", sBLK){

				href( (sNM): "enableAPIPageLink",(sTIT): "Enable API", description: sBLK, page: "enableAPIPage")
			}
		}else{
			hubiForm_section("Tile Options", 1, "tune", sBLK){
				container=[]
				container << hubiForm_page_button("Select Device/Data", "deviceSelectionPage", "100%", "vibration")
				container << hubiForm_page_button("Configure Tile", "graphSetupPage", "100%", "poll")
				hubiForm_container(container, 1)
			}

			if(day_num){
				local_graph_url()
				hubiForm_section("Configure Tile - Desktop Only", 10, "settings", sBLK, sBLK){
					container=[]
					container << getPreviewWindow("tile_settings_HTML", "mainPage")
					hubiForm_container(container, 1)
				}
				install_tile()
			}

			put_settings()
		}
	}
}

def verifyDeviceCallback(response, data){
}

String getPreviewWindow(String var, String page){

	Map params=[
			uri: "${state.localEndpointURL}",
			path: "graph/?access_token=${state.endpointSecret}",
			requestContentType: "application/json",
	]

	asynchttpGet(verifyDeviceCallback, params)

	if(!settings["$var"]){ wremoveSetting(var.toString()) }

	String html
	html="""
<style>
	.iframe-container{
		overflow: hidden
		width: 55vmin
		height: 65vmin
		position: relative
	}
	.iframe-container iframe{
		border: 0
		left: 0
		position: absolute
		top: 0
	}
</style>

"""
	//<input type="text" id="settings${var}" name="settings[${var}]" value="${settings[var]}" style="display: none;" >
	//<div class="form-group" style="display:none;">
	//   <input type="hidden" name="${var}.type" value="text">
	//   <input type="hidden" name="${var}.multiple" value="false">
	//</div>
	//<div>
	html+="""
<div class="iframe-container">
	<iframe id="preview_frame" style="width: 100%; height: 100%; position: relative; z-index: 1; background-image: url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAEq2lUWHRYTUw6Y29tLmFkb2JlLnhtcAAAAAAAPD94cGFja2V0IGJlZ2luPSLvu78iIGlkPSJXNU0wTXBDZWhpSHpyZVN6TlRjemtjOWQiPz4KPHg6eG1wbWV0YSB4bWxuczp4PSJhZG9iZTpuczptZXRhLyIgeDp4bXB0az0iWE1QIENvcmUgNS41LjAiPgogPHJkZjpSREYgeG1sbnM6cmRmPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5LzAyLzIyLXJkZi1zeW50YXgtbnMjIj4KICA8cmRmOkRlc2NyaXB0aW9uIHJkZjphYm91dD0iIgogICAgeG1sbnM6ZXhpZj0iaHR0cDovL25zLmFkb2JlLmNvbS9leGlmLzEuMC8iCiAgICB4bWxuczp0aWZmPSJodHRwOi8vbnMuYWRvYmUuY29tL3RpZmYvMS4wLyIKICAgIHhtbG5zOnBob3Rvc2hvcD0iaHR0cDovL25zLmFkb2JlLmNvbS9waG90b3Nob3AvMS4wLyIKICAgIHhtbG5zOnhtcD0iaHR0cDovL25zLmFkb2JlLmNvbS94YXAvMS4wLyIKICAgIHhtbG5zOnhtcE1NPSJodHRwOi8vbnMuYWRvYmUuY29tL3hhcC8xLjAvbW0vIgogICAgeG1sbnM6c3RFdnQ9Imh0dHA6Ly9ucy5hZG9iZS5jb20veGFwLzEuMC9zVHlwZS9SZXNvdXJjZUV2ZW50IyIKICAgZXhpZjpQaXhlbFhEaW1lbnNpb249IjIiCiAgIGV4aWY6UGl4ZWxZRGltZW5zaW9uPSIyIgogICBleGlmOkNvbG9yU3BhY2U9IjEiCiAgIHRpZmY6SW1hZ2VXaWR0aD0iMiIKICAgdGlmZjpJbWFnZUxlbmd0aD0iMiIKICAgdGlmZjpSZXNvbHV0aW9uVW5pdD0iMiIKICAgdGlmZjpYUmVzb2x1dGlvbj0iNzIuMCIKICAgdGlmZjpZUmVzb2x1dGlvbj0iNzIuMCIKICAgcGhvdG9zaG9wOkNvbG9yTW9kZT0iMyIKICAgcGhvdG9zaG9wOklDQ1Byb2ZpbGU9InNSR0IgSUVDNjE5NjYtMi4xIgogICB4bXA6TW9kaWZ5RGF0ZT0iMjAyMC0wNi0wMlQxOTo0NzowNS0wNDowMCIKICAgeG1wOk1ldGFkYXRhRGF0ZT0iMjAyMC0wNi0wMlQxOTo0NzowNS0wNDowMCI+CiAgIDx4bXBNTTpIaXN0b3J5PgogICAgPHJkZjpTZXE+CiAgICAgPHJkZjpsaQogICAgICBzdEV2dDphY3Rpb249InByb2R1Y2VkIgogICAgICBzdEV2dDpzb2Z0d2FyZUFnZW50PSJBZmZpbml0eSBQaG90byAxLjguMyIKICAgICAgc3RFdnQ6d2hlbj0iMjAyMC0wNi0wMlQxOTo0NzowNS0wNDowMCIvPgogICAgPC9yZGY6U2VxPgogICA8L3htcE1NOkhpc3Rvcnk+CiAgPC9yZGY6RGVzY3JpcHRpb24+CiA8L3JkZjpSREY+CjwveDp4bXBtZXRhPgo8P3hwYWNrZXQgZW5kPSJyIj8+IC4TuwAAAYRpQ0NQc1JHQiBJRUM2MTk2Ni0yLjEAACiRdZE7SwNBFEaPiRrxQQQFLSyCRiuVGEG0sUjwBWqRRPDVbDYvIYnLboIEW8E2oCDa+Cr0F2grWAuCoghiZWGtaKOy3k2EBIkzzL2Hb+ZeZr4BWyippoxqD6TSGT0w4XPNLyy6HM/UYqONfroU1dBmguMh/h0fd1RZ+abP6vX/uYqjIRI1VKiqEx5VNT0jPCk8vZbRLN4WblUTSkT4VLhXlwsK31p6uMgvFseL/GWxHgr4wdYs7IqXcbiM1YSeEpaX404ls+rvfayXNEbTc0HJnbI6MAgwgQ8XU4zhZ4gBRiQO0YdXHBoQ7yrXewr1s6xKrSpRI4fOCnESZOgVNSvdo5JjokdlJslZ/v/11YgNeovdG31Q82Sab93g2ILvvGl+Hprm9xHYH+EiXapfPYDhd9HzJc29D84NOLssaeEdON+E9gdN0ZWCZJdli8Xg9QSaFqDlGuqXip797nN8D6F1+aor2N2DHjnvXP4Bhcln9Ef7rWMAAAAJcEhZcwAACxMAAAsTAQCanBgAAAAXSURBVAiZY7hw4cL///8Z////f/HiRQBMEQrfQiLDpgAAAABJRU5ErkJggg=='); background-size: 25px; background-repeat: repeat; image-rendering: pixelated;" src="${state.localEndpointURL}graph/?access_token=${state.endpointSecret}" data-fullscreen="false"
		onload="(() =>{
	})()""></iframe>
</div>
"""
	return cleanHtml(html)
}



def processCallBack(response, data){
}


private static String getAbbrev(String unit){
	switch (unit){
		case sNONE: return ""
		case sFAHR: return "&deg;"
		case sCELS: return "&deg;"
		case "kelvin": return "K"
		case "meters_per_second": return "m/s"
		case "miles_per_hour": return "mph"
		case "knots": return "kn"
		case "millimeters": return "mm"
		case "inches": return '"'
		case "degrees": return "&deg;"
		case "radians": return "rad"
		case "cardinal": return ""
		case "trend_numeric": return ""
		case "trend_text": return ""
		case "percent_numeric": return "%"
		case "millibars": return "mbar"
		case "millimeters_mercury": return "mmHg"
		case "inches_mercury": return "inHg"
		case "hectopascal": return "hPa"
		case "kilometers_per_hour" : return "km/h"
	}
	return ""
}

private Map getUnits(unit, ival){
	if(unit == null)  return [(sNM): "unknown", var: "tbd", units: sNONE]

	try{
		switch (unit.toLowerCase()){
			case "f":
			case "°f":
				return [(sNM): "Fahrenheit (°F)", var: sTEMP, units: sFAHR]
			case "c":
			case "°c":
				return [(sNM): "Celsius (°C)", var: sTEMP, units: sCELS]
			case "mph":
				return [(sNM): "Miles per Hour (mph)", var: "velocity", units: "miles_per_hour"]
			case "m/s":
				return [(sNM): "Meters per Second (m/s)", var: "velocity", units: "meters_per_second"]
			case "in":
			case '"':
				return [(sNM): 'Inches (")', var: "depth", units: "inches"]
			case "mm":
//			case '"':
				return [(sNM): 'Millimeters (mm)', var: "depth", units: "millimeters"]
			case "°":
			case "deg":
				return [(sNM): "Degrees (°)", var: "direction", units: "degrees"]
			case "rad":
				return [(sNM): "Radians (°)", var: "direction", units: "radians"]
			case "inhg":
				return [(sNM): "Inches of Mercury (inHg)", var: "pressure", units: "inches_mercury"]
			case "mmhg":
				return [(sNM): "Millimeters of Mercury mmHg)", var: "pressure", units: "millimeters_mercury"]
			case "mbar":
				return [(sNM): "Millibars (mbar)", var: "pressure", units: "millibars"]
			case "km/h":
				return [(sNM): "Kilometers per hour (km/h)", var: "velocity", units: "kilometers_per_hour"]
			case "hPa":
				return [(sNM): "Hectopascal (hPa)", var:"pressure", units: "hectopascal"]
			case "%":
				Double value=Double.parseDouble(ival)
				if(value > 1.0 && value < 100.0){
					return [(sNM): "Percent (0 to 100)", var:"percent", units: "percent_numeric"]
				}else if(value >=0.0 && value < 1.0){
					return [(sNM): "Percent (0.1 to 1.0)", var: "percent", units: "percent_decimal"]
				}
			default:
				break
		}
	}catch(ex){
		error("Unable to find units: $unit",null,iN2,ex)
	}
	return [(sNM): "unknown", var: "tbd", units: sNONE]

}

static List<Map> getIconList(){

	return [
			[(sNM): "None",				(sICON): "alpha-x-circle-outline"],
			[(sNM): "Cloudy",			(sICON): "weather-cloudy"],
			[(sNM): "Cloudy Alert",		(sICON): "weather-cloudy-alert"],
			[(sNM): "Cloudy Right Arrow",	(sICON): "weather-cloudy-arrow-right"],
			[(sNM): "Fog",				(sICON): "weather-fog"],
			[(sNM): "Hail",				(sICON): "weather-hail"],
			[(sNM): "Hazy",				(sICON): "weather-hazy"],
			[(sNM): "Hurricane",			(sICON): "weather-hurricane"],
			[(sNM): "Lightning",			(sICON): "weather-lightning"],
			[(sNM): "Lightning Raining",	(sICON): "weather-lightning-rainy"],
			[(sNM): "Night",				(sICON): "weather-night"],
			[(sNM): "Night Partly Cloudy",   (sICON): "weather-night-partly-cloudy"],
			[(sNM): "Partly Cloudy",		(sICON): "weather-partly-cloudy"],
			[(sNM): "Partly Lightning",	(sICON): "weather-partly-lightning"],
			[(sNM): "Partly Raining",	(sICON): "weather-partly-rainy"],
			[(sNM): "Partly Snowing",	(sICON): "weather-partly-snowy"],
			[(sNM): "Partly Snowing Raining",icon: "weather-partly-snowy-rainy"],
			[(sNM): "Pouring",			(sICON): "weather-pouring"],
			[(sNM): "Raining",			(sICON): "weather-rainy"],
			[(sNM): "Snowing",			(sICON): "weather-snowy"],
			[(sNM): "Heavy Snow",		(sICON): "weather-snowy-heavy"],
			[(sNM): "Snowing Raining",	(sICON): "weather-snowy-rainy"],
			[(sNM): "Sunny",				(sICON): "weather-sunny"],
			[(sNM): "Sunny Alert",		(sICON): "weather-sunny-alert"],
			[(sNM): "Sunny Off",			(sICON): "weather-sunny-off"],
			[(sNM): "Sunset",			(sICON): "weather-sunset"],
			[(sNM): "Sunset Down",		(sICON): "weather-sunset-down"],
			[(sNM): "Sunset Up",			(sICON): "weather-sunset-up"],
			[(sNM): "Tornado",			(sICON): "weather-tornado"],
			[(sNM): "Windy",				(sICON): "weather-windy"],
			[(sNM): "Windy 2",			(sICON): "weather-windy-variant"],
			[(sNM): "Home Thermometer",	(sICON): "home-thermometer-outline"],
			[(sNM): "Arrow Up",			(sICON): "arrow-up-thick"],
			[(sNM): "Arrow Down",		(sICON): "arrow-down-thick"],
			[(sNM): "Umbrella",			(sICON): "umbrella-outline"],
			[(sNM): "Ruler",				(sICON): "ruler"],
			[(sNM): "Cloud Question",	(sICON): "cloud-question"],
			[(sNM): "Calendar",			(sICON): "calendar-today"],
			[(sNM): "Tail Wind",			(sICON): "tailwind"],
			[(sNM): "Compass",			(sICON): "compass-outline"],
			[(sNM): "Gauge",				(sICON): "gauge"],
			[(sNM): "Thermostat",		(sICON): "thermostat"],
			[(sNM): "Water Percent",		(sICON): "water-percent"],
			[(sNM): "Wave",				(sICON): "wave"],
			[(sNM): "Snow",				(sICON): "snowflake"],
			[(sNM): "Water",				(sICON): "water"],]
}

Map getOptions_weather2(){

	Map options=[
			"tile_units": state.unit_type,
			"openweather_refresh_rate": openweather_refresh_rate ? openweather_refresh_rate : "300000",
			"tiles" :	(List)state.tile_settings,
			"tile_type" : (Map)state.tile_type,
			"new_tile_dialog" : state.newTileDialog,
			"api_code" :  "${state.endpointSecret}",
			"url" :	"${state.localEndpointURL}",
	]

	options.out_units=[:]

	((Map<String,Map>)state.unit_type).each{String key, Map measurement->
		options.out_units << [ (key) : settings["${key}_units"]]
	}

	return options
}

def getMapData(map, String loc){
	List<String> splt=loc.tokenize('.')
	def cur
	cur=map
	splt.each{String str->
		try{
			if(str.isNumber()){
				Integer num=str.toInteger()
				cur=cur!=null ? cur[num] : null
			}else{
				cur=cur!=null ? cur[str] : null
			}
		}catch(e){
			log.debug(loc+": Cannot find data: "+e)
			return null
		}
	}
	return cur
}

static String applyDecimals(Map tile, val){

	String value
	value=val.toString()
	if(value.isNumber()){
		def num_decimals=tile.decimals
		value=sprintf("%.${num_decimals}f", value.toFloat())
		return value
	}
	else return value
}

static String getWindDirection(idirection){
	def direction
	direction=idirection
	direction=Float.parseFloat(direction.toString())
	if(direction > 348.75 || direction < 11.25) return "N"
	if(direction >= 11.25 && direction < 33.75) return "NNE"
	if(direction >= 33.75 && direction < 56.25) return "NE"
	if(direction >= 56.25 && direction < 78.7) return "ENE"
	if(direction >= 78.75 && direction < 101.25) return "E"
	if(direction >= 101.25 && direction < 123.75) return "ESE"
	if(direction >= 123.75 && direction < 146.25) return "SE"
	if(direction >= 146.25 && direction < 168.75) return "SSE"
	if(direction >= 168.75 && direction < 191.25) return "S"
	if(direction >= 191.25 && direction < 213.75) return "SSW"
	if(direction >= 213.75 && direction < 236.25) return "SW"
	if(direction >= 236.25 && direction < 258.75) return "WSW"
	if(direction >= 258.75 && direction < 281.25) return "W"
	if(direction >= 281.25 && direction < 303.75) return "WNW"
	if(direction >= 303.75 && direction < 326.25) return "NW"
	if(direction >= 326.25 && direction < 348.75) return "NNW"
	return 'Bad'
}

def applyConversion(Map tile, ival){

	def val
	val=ival
	Map tile_type
	String out_units, in_units
	out_units=sBLK
	in_units=sBLK
	String sUNS='UNSUPPORTED'
	try{
		tile_type=((Map<String,Map>)state.tile_type)."${tile.type}"
		out_units=((Map<String,Map>)state.unit_type)."${tile_type.type}".out
		in_units=tile_type.in_units
	}catch(ignored){
		log.debug("Unable to find units for ${tile.title}:: Input units="+in_units+"  Output units="+out_units)
		return sUNS
	}

	if(in_units != out_units && out_units != sNONE)
		switch (in_units){
		//Temperature
			case sCELS:
				switch (out_units){
					case sFAHR: val=(val * 9 / 5) + 32; break
					case "kelvin": val=val + 273.15; break
					default: val=sUNS
				}
				break
			case sFAHR:
				switch (out_units){
					case sCELS: val=(val - 32.0) * (5 / 9); break
					case "kelvin":  val=((val - 32) * (5 / 9)) + 273.15; break
					default: val=sUNS
				}
				break
			case "kelvin":
				switch (out_units){
					case sFAHR: val=((val - 273.15) * (9 / 5)) + 32; break
					case sCELS: val=(val - 273.15); break
					default: val=sUNS
				}
				break

				//Precipitation
			case "millimeters":
				if(out_units == "inches"){
					val=(val / 25.4)
				}else val=sUNS
				break
			case "inches":
				if(out_units == "millimeters"){
					val=(val * 25.4)
				}else val=sUNS
				break

				//Velocity
			case "meters_per_second":
				switch (out_units){
					case "miles_per_hour": val=(val * 2.237); break
					case "knots": val=(val * 1.944); break
					case "kilometers_per_hour": val=(val * 3.6); break
					default: val=sUNS
				}
				break
			case "miles_per_hour":
				switch (out_units){
					case "miles_per_hour": val=(val / 2.237); break
					case "knots": val=(val / 1.151); break
					case "kilometers_per_hour": val=(val * 1.609); break
					default: val=sUNS
				}
				break
			case "knots":
				switch (out_units){
					case "miles_per_hour": val=(val * 1.151); break
					case "meters_per_second": val=(val / 1.944); break
					case "kilometers_per_hour": val=(val * 1.852); break
					default: val=sUNS
				}
				break
			case "kilometers_per_hour":
				switch (out_units){
					case "miles_per_hour": val=(val / 1.609); break
					case "meters_per_second": val=(val / 3.6); break
					case "knots": val=(val / 1.852); break
					default: val=sUNS
				}
				break

				//Pressure
			case "hectopascal":
			case "millibars":
				switch (out_units){
					case "inches_mercury": val=(val / 33.864); break
					case "millimeters_mercury": val=(val / 1.333); break
					case "hectopascal": break
					default: val=sUNS
				}
				break
			case "inches_mercury":
				switch (out_units){
					case "hectopascal":
					case "millibars": val=(val * 33.864); break
					case "inches_mercury": val=(val / 25.4); break
					default: val=sUNS
				}
				break
			case "millimeters_mercury":
				switch (out_units){
					case "hectopascal":
					case "millibars": val=(val * 1.333); break
					case "millimeters_mercury": val=(val * 25.4); break
					default: val=sUNS
				}
				break
			case "degrees":
				switch (out_units){
					case "cardinal":
						val=getWindDirection(val)
						break
					case "radians": val=(val / 180.0) * 3.1415926535; break
					default: val=sUNS
				}
				break
			case "radians":
				switch (out_units){
					case "cardinal":
						val=getWindDirection(( (val * 180) / 3.1415926535) )
						break
					case "degrees": val=((val * 180) / 3.1415926535); break
					default: val=sUNS
				}
				break
			case "cardinal":
				switch (val){
					case "N": val=0; break
					case "NNE": val=22.5; break
					case "NE": val=45; break
					case "ENE": val=67.5; break
					case "E": val=90; break
					case "ESE": val=112.5; break
					case "SE": val=135; break
					case "SSE": val=157.5; break
					case "S": val=180; break
					case "SSW": val=202.5; break
					case "SW": val=225; break
					case "WSW": val=247.5; break
					case "W":val=270; break
					case "WNW":  val=292.5; break
					case "NW":  val=315; break
					case "NNW":  val=337.5; break
					default:   val=sUNS
				}
				if(val != sUNS){
					switch (out_units){
						case "radians": val=((val / 180 ) * 3.1415926535) ; break
						case "degrees": val=val; break
						default: val=sUNS
					}
				}
				break

				//TEXT CONVERSIONS
			case "time_seconds":
				Long v=val*1000L
				Date d=new Date(v)

				switch (out_units){
					case "time_twelve":
						SimpleDateFormat simpDate
						simpDate=new SimpleDateFormat("h:mm")
						val=simpDate.format(d)
						break
					case "time_two_four":
						SimpleDateFormat simpDate
						simpDate=new SimpleDateFormat("HH:mm")
						val=simpDate.format(d)
						break
					default:
						val=sUNS
				}
				break
			case "time_milliseconds":
				Date d=new Date(val)

				switch (out_units){
					case "time_twelve":
						val=d.getTimeString()
						break
					case "time_two_four":
						val=d.getTimeString()
						break
					default:
						val=sUNS
				}
				break
			case "percent_numeric":
				if(out_units == "percent_decimal") val=val / 100.0
				else val=sUNS
				break
			case "percent_decimal":
				if(out_units == "percent_numeric") val=val * 100.0
				else val=sUNS
				break
		}
	return val
}


@Field final List<Map<String,String>>pairingsFLD=[
		[(sNM): "thunderstorm with light rain",		(sICON): "weather-lightning-rainy"],
		[(sNM): "thunderstorm with rain",			(sICON): "weather-lightning-rainy"],
		[(sNM): "thunderstorm with heavy rain",		(sICON): "weather-lightning-rainy"],
		[(sNM): "light thunderstorm",				(sICON): "weather-lightning"],
		[(sNM): "thunderstorm",						(sICON): "weather-lightning"],
		[(sNM): "heavy thunderstorm",				(sICON): "weather-lightning"],
		[(sNM): "ragged thunderstorm",				(sICON): "weather-lightning"],
		[(sNM): "thunderstorm with light drizzle",  (sICON): "weather-lightning-rainy"],
		[(sNM): "thunderstorm with drizzle",		(sICON): "weather-lightning-rainy"],
		[(sNM): "thunderstorm with heavy drizzle",  (sICON): "weather-lightning-rainy"],
		[(sNM): "light intensity drizzle",			(sICON): "weather-partly-rainy"],
		[(sNM): "drizzle",							(sICON): "weather-partly-rainy"],
		[(sNM): "heavy intensity drizzle",			(sICON): "weather-partly-rainy"],
		[(sNM): "light intensity drizzle rain",		(sICON): "weather-partly-rainy"],
		[(sNM): "drizzle rain",						(sICON): "weather-partly-rainy"],
		[(sNM): "heavy intensity drizzle rain",		(sICON): "weather-rainy"],
		[(sNM): "shower rain and drizzle",			(sICON): "weather-rainy"],
		[(sNM): "heavy shower rain and drizzle",	(sICON): "weather-pouring"],
		[(sNM): "shower drizzle",					(sICON): "weather-rainy"],
		[(sNM): "light rain",						(sICON): "weather-rainy"],
		[(sNM): "moderate rain",					(sICON): "weather-pouring"],
		[(sNM): "heavy intensity rain",				(sICON): "weather-pouring"],
		[(sNM): "very heavy rain",					(sICON): "weather-pouring"],
		[(sNM): "extreme rain",						(sICON): "weather-pouring"],
		[(sNM): "freezing rain",					(sICON): "weather-snowy-rainy"],
		[(sNM): "light intensity shower rain",		(sICON): "weather-rainy"],
		[(sNM): "shower rain",						(sICON): "weather-rainy"],
		[(sNM): "heavy intensity shower rain",		(sICON): "weather-pouring"],
		[(sNM): "ragged shower rain",				(sICON): "weather-partly-rainy"],
		[(sNM): "light snow",						(sICON): "weather-snowy"],
		[(sNM): "snow",								(sICON): "weather-snowy"],
		[(sNM): "heavy snow",						(sICON): "weather-snowy-heavy"],
		[(sNM): "sleet",							(sICON): "weather-hail"],
		[(sNM): "light shower sleet",				(sICON): "weather-hail"],
		[(sNM): "shower sleet",						(sICON): "weather-hail"],
		[(sNM): "light rain and snow",				(sICON): "weather-snowy-rainy"],
		[(sNM): "rain and snow",					(sICON): "weather-snowy-rainy"],
		[(sNM): "light shower snow",				(sICON): "weather-partly-snowy"],
		[(sNM): "shower snow",						(sICON): "weather-partly-snowy"],
		[(sNM): "heavy shower snow",				(sICON): "weather-partly-snowy"],
		[(sNM): "mist",								(sICON): "weather-fog"],
		[(sNM): "smoke",							(sICON): "weather-fog"],
		[(sNM): "haze",								(sICON): "weather-hazy"],
		[(sNM): "sand dust whirls",					(sICON): "weather-tornado"],
		[(sNM): "fog",								(sICON): "weather-fog"],
		[(sNM): "sand",								(sICON): "weather-fog"],
		[(sNM): "dust",								(sICON): "weather-fog"],
		[(sNM): "volcanic ash",						(sICON): "weather-fog"],
		[(sNM): "squalls",							(sICON): "weather-tornado"],
		[(sNM): "tornado",							(sICON): "weather-tornado"],
		[(sNM): "clear sky night",					(sICON): "weather-night"],
		[(sNM): "clear sky",						(sICON): "weather-sunny"],
		[(sNM): "few clouds night",					(sICON): "weather-night-partly-cloudy"],
		[(sNM): "few clouds",						(sICON): "weather-partly-cloudy"],
		[(sNM): "scattered clouds night",			(sICON): "weather-night-partly-cloudy"],
		[(sNM): "scattered clouds",					(sICON): "weather-partly-cloudy"],
		[(sNM): "broken clouds",					(sICON): "weather-cloudy"],
		[(sNM): "overcast clouds",					(sICON): "weather-cloudy"]
]


List<String> translateCondition(Map tile, String condition){

	//String icon="mdi-weather-sunny-off"

	List return_val
	try{
		Date now
		now=new Date()
		String period=sMs(tile,'period')
		List<String> timeframe=period.split("\\.")
		Boolean round_hour
		round_hour=false

		if(timeframe[0] == "hourly"){
			round_hour=true
			Integer num_hours=timeframe[1].toInteger()
			use( TimeCategory ){
				now=now + num_hours.hours
			}
		}

		String check_condition
		check_condition=condition
		if(isNight(now, round_hour)){
			check_condition+=" night"
		}
		return [sICON, pairingsFLD.find{Map<String,String> el->  sMs(el,sNM) == check_condition}[sICON]]

	}catch(ignored){}

	try{
		return [sICON, pairingsFLD.find{Map<String,String> el->  sMs(el,sNM) == condition}[sICON]]
	}catch(ignored){}

	return_val=[sICON, "alert-circle"]
	return return_val
}

List<String> formatNumericData(Map tile, ival){
	def val
	val=ival
	if(val == null)
		val=0
	return [(sVAL), applyDecimals(tile, applyConversion(tile, val))]
}

static Float getMinHour(Date date){
	return (date.getHours())+(date.getMinutes()/60.0) as Float
}

Boolean isNight(Date date, Boolean round_hour){
	Float sunrise=getMinHour((Date)location.sunrise)
	Float sunset=getMinHour((Date)location.sunset)
	Float now=round_hour ? date.getHours().toFloat() : getMinHour(date)
	//Calendar cal=Calendar.getInstance()

	return now < sunrise || now > sunset
}

List formatHourData(Map tile, val){

	Long val_micro=val*1000L
	Date date=new Date(val_micro)

	switch (settings["time_units"]){
		case "time_seconds" :		return [sVAL, val]
		case "time_milliseconds" :  return [sVAL, val_micro]
		case "time_twelve" :		return [sVAL, date.format('h:mm a', mTZ())]
		case "time_two_four" :		return [sVAL, date.format('HH:mm', mTZ())]
	}
	return [sVAL,  "XXXX"]
}

List<String> formatDayData(Map tile, val){

	Long val_micro=val*1000L
	Date date=new Date (val_micro)

	String day
	if(settings["day_units"] == "short") day=date.format('E', mTZ())
	else day=date.format('EEEE', mTZ())

	return [sVAL,  day]
}

List<String> formatTextData(Map tile, String val){

	switch (settings["text_units"]){
		case "plain":	return [sVAL, val]
		case "lowercase":  return [sVAL, val.toLowerCase()]
		case "uppercase":  return [sVAL, val.toUpperCase()]
		case "title":	return [sVAL, val.split(sSPC).collect{ String it ->it.capitalize()}.join(sSPC)]
	}
	return [sVAL, val]
}


/*
List<String> formatConditionText(Map tile, String val){
	return ["value", val.split(sSPC).collect{it.capitalize()}.join(sSPC)]
}

List<String> formatTitle(Map tile, String val){
	return["value", ""]
}

List formatPressure(Map tile, val){
	return ["value", "Pressure Trend"]
}

List<String> formatDewPoint(Map tile, val){
	// TODO does not deal with C
	def dewPoint=val
	String text
	text=""

	if(dewPoint < 50) text="DRY"
	else if(dewPoint < 55) text= "NORMAL"
	else if(dewPoint < 60) text= "OPTIMAL"
	else if(dewPoint < 65) text= "STICKY"
	else if(dewPoint < 70) text= "MOIST"
	else if(dewPoint < 75) text= "WET"
	else text "MISERABLE"

	return ["value", text]
}
*/

def getSensorData1(String measurement){
	Long device_id=(measurement.tokenize('.')[1]).toLong()
	String attribute=measurement.tokenize('.')[2]

	def sensor=sensors.find{ it.id == device_id }
	return sensor.currentState(attribute,true).getValue()
}

void buildWeatherData(){

	if(isEric())debug "buildWeatherData",null
	//def selections=settings["tile_settings"]

//	String tdata=parent.getOpenWeatherData() // TODO parent.getWData()
//	Map data=parseJson(tdata)
	Map data=parent.getWData()
	//log.debug "buildWeatherData got ${data.size()}"

	List<Map> temp=(List<Map>)state.tile_settings
	temp.eachWithIndex{Map tile, index->
		def val, rain_val, snow_val
		val=null
		rain_val=null
		snow_val=null
		String period, measurement
		period=sBLK
		measurement=sBLK
		try{
			period=tile.period
			measurement=state.tile_type."${tile.type}".ow
			if(period == "sensor"){
				val=getSensorData1(measurement)
			}else if(measurement == "precipitation"){
				rain_val=getMapData(data, period+".rain")
				snow_val=getMapData(data, period+".snow")

				//Special Case
				if(rain_val == null) rain_val=0
				if(snow_val == null) snow_val=0

				if(snow_val > rain_val){
					tile.icon="snowflake"
				}else{
					tile.icon="water"
				}
				val=rain_val + snow_val

			}else if(period != sNONE && measurement != sNONE){
				val=getMapData(data, period+"."+measurement)
				//log.debug "getMapData ${period}.${measurement} val $val"

			}
		}catch(ignored){
			log.debug(sMs(tile,sNM)+": Unable to get data: "+period+", "+measurement)
		}

		String unit_type=state.tile_type."${tile.type}".type
		String parse_func=((Map<String,Map>)state.unit_type)."${unit_type}".parse_func
		if(parse_func!=sNONE){
			try{
				List returnVal="${parse_func}"(tile, val)
				tile."${returnVal[0]}"=returnVal[1]
				//log.debug "parse_func: ${parse_func} tile $returnVal"
			}catch(ex){
				log.debug(val+sSPC+unit_type+sSPC+parse_func+"::: Issue executing parse function: $parse_func " + ex)
			}
		}else{
			tile[sVAL]=""
		}
	}
	state.tile_settings=temp
}

String getTileHTML(Map item, Boolean locked){
	String var=sMs(item,'var')

	BigDecimal fontScale=4.6
	BigDecimal lineScale=0.85
	BigDecimal iconScale=3.5
	//def header=0.1

	Integer height=item.h
	String html
	html=""
	String tile_locked=locked ? "false" : "true"
	String background=getRGBA(item.background_color, (Float.parseFloat(item.background_opacity.toString())))
	String font=getRGBA(item.font_color, Float.parseFloat(item.font_opacity.toString()))

	if(item.display==true){
		html += """ <div id="${var}_tile_main" class="grid-stack-item" data-gs-id="${var}" data-gs-x="${item.baseline_column}"
			data-gs-y="${item.baseline_row}" data-gs-width="${item.w}" data-gs-height="${height}" data-gs-locked="${tile_locked}"
			ondblclick="setOptions('${var}')">

			<div id="${var}_title" style="display: none;">${item.title}</div>
			<div id="${var}_font_adjustment" style="display: none;">${item.font_adjustment}</div>
			<div class="mdl-tooltip" for="${var}_tile_main" style="background-color: rgba(255,255,255,0.75); color: rgba(0,0,0,100);)">${item.title}</div>

			<div id="${var}_tile" class="grid-stack-item-content" style="font-size: ${fontScale*height}vh;
								line-height: ${fontScale*lineScale*height}vh;
								text-align: ${item.justification};
								background-color: ${background};
								font-weight: ${item.font_weight};">
"""

		//Compute Icon and other spacing

		//Left Icon
		if(item.icon_loc != "right"){
			item.icon_space=item.icon_space ?:  ""
			html+="""<span id="${var}_icon" class="mdi mdi-${item.icon}" style="font-size: ${iconScale*height}vh; color: ${font};">${item.icon_space}</span>"""
		}
		//Text
		if(item.text == "null" || item.text == null) item.text=""
		html+="""<span id="${var}_text" style="color: ${font};">${item.text}</span>"""

		//Main Content
		html += """<span id="${var}" style="color: ${font};">${item[sVAL]}</span>"""

		String tile_type
		String out_units
		String units
		//Units
		try{
			tile_type=state.tile_type."${item.type}".type
			out_units=state.unit_type."${tile_type}".out
			units=getAbbrev(out_units)
		}catch(ignored){
			units=""
		}

		if(units == "unknown") units=""

		//Unit Spacing
		html += """<span id="${var}_unit_space">${item.unit_space}</span>"""

		html += """<span id="${var}_units" style="font-size: ${iconScale*height}vh; color: ${font};">${units}</span>"""

		//Right Icon
		if(item.icon_loc == "right"){
			html+="""<span>${item.icon_space}</span>"""
			html+="""<span id="${var}_icon" class="mdi mdi-${item.icon}" style="color: ${font};"></span>"""
		}
		html += """</div></div>"""
	}

	return html
}
/*

def getDrawType(){
   return "google.visualization.LineChart"
}

static String removeLastChar(String str){
	str.subSequence(0, str.length() - 1)
	str
}

*/
// weather2
String defineHTML_Header(){

	String html="""
	<link rel="stylesheet" href="//cdn.materialdesignicons.com/5.4.55/css/materialdesignicons.min.css">
	<link rel="stylesheet" href="https://fonts.googleapis.com/icon?family=Material+Icons">
	<link rel="stylesheet" href="https://code.getmdl.io/1.3.0/material.indigo-pink.min.css">
	<link rel="stylesheet" href="https://maxcdn.bootstrapcdn.com/bootstrap/4.3.1/css/bootstrap.min.css">
	<link rel="stylesheet" href="/local/${isSystemType() ? 'webcore/' : ''}f06ea400-fe7a-49ef-8c50-6418f0a78dc6-WeatherTile2.css">
	<script>
		const localURL =		"${state.localEndpointURL}";
		const secretEndpoint=	"${state.endpointSecret}";
		const latitude =		"${latitude}";
		const longitude =		"${longitude}";
		const tile_key =		"${tile_key}";
	</script>

	<script defer src="https://code.getmdl.io/1.3.0/material.min.js"></script>
	<script src="https://code.jquery.com/jquery-3.5.1.min.js" integrity="sha256-9/aliU8dGd2tb6OSsuzixeV4y/faTqgFtohetphbbj0=" crossorigin="anonymous"></script>
	<script src="https://code.jquery.com/ui/1.12.1/jquery-ui.min.js" integrity="sha256-VazP97ZCwtekAsvgPBSUwPFKdrwD3unUfSGVYrahUqU=" crossorigin="anonymous"></script>
	<script type="text/javascript" src="https://unpkg.com/@fonticonpicker/fonticonpicker/dist/js/jquery.fonticonpicker.min.js"></script>
	<script src="https://cdn.jsdelivr.net/npm/gridstack@1.1.2/dist/gridstack.js"></script>
	<script src="https://cdn.jsdelivr.net/npm/gridstack@1.1.2/dist/gridstack.jQueryUI.js"></script>
	<script type="text/javascript" src="https://www.google.com/jsapi"></script>
	<script src="https://cdnjs.cloudflare.com/ajax/libs/jqueryui-touch-punch/0.2.3/jquery.ui.touch-punch.min.js" integrity="sha512-0bEtK0USNd96MnO4XhH8jhv3nyRF0eK87pJke6pkYf3cM0uDIhNJy9ltuzqgypoIFXw3JSuiy04tVk4AjpZdZw==" crossorigin="anonymous"></script>
	<script defer src="/local/${isSystemType() ? 'webcore/' : ''}ba8d5ae0-1fbd-430a-bae0-bb5c0bd17ebd-WeatherTile2.js"></script>
	"""
	return html
}

static String addColorPicker(Map map){
	String var=sMs(map,'var')
	String title=sMs(map,sTIT)

	String html="""
	<div class="border-container">

		<div id="text_box" class="flex-container">
			<div class="flex-item" style="flex-basis: 25%;">
				<span><label for="${var}_color">${title}</label></span>
			</div>
			<div class="flex-item" style="flex-basis: 75%;">
				<span><label for="${var}_color">Opacity</label></span>
			</div>
		</div>

	  <div id="text_color_box" class="flex-container">
		  <div class="flex-item" style="flex-basis: 25%;">
			 <span><input type="color" id="${var}_color" name="${var}_color" value="#FFFFFF"></span>
		  </div>
		  <div class="flex-item" style="flex-basis: 60%;">
			 <input id="${var}_slider" class="mdl-slider mdl-js-slider" type="range" min="0" max="100" value="100" tabindex="0"
			  oninput="${var}_showMessage(this.value)" onchange="${var}_showMessage(this.value)">
		 </div>
		 <div class="flex-item" style="flex-basis: 15%;">
			<div class="item" id="${var}_message">100%</div>
		</div>
	  </div>
	</div>
	  <!-- JAVASCRIPT -->
	  <script language="javascript">
		function ${var}_showMessage(value){
		  document.getElementById("${var}_message").innerHTML=value + "%";
		}
	  </script>
"""
	return html
}

static String addButtonMenu(Map map){
	String button_var=sMs(map,'var_name')
	def default_val=map.default_value
	String default_icon=sMs(map,'default_icon')
	List<Map>item_list=map.list
	String tooltip=sMs(map,'tooltip') ?: ""
	String side=sMs(map,'side') ?: "left"

	String html
	html="""
		<div id="${button_var}_value" style="display: none;">${default_val}</div>
		<div id="${button_var}_icon" style="display: none;">${default_icon}</div>
		<button id="${button_var}_button"
			class="mdl-button mdl-js-button mdl-button--icon mdi mdi-${default_icon}">
		</button>

		<div class="mdl-tooltip" for="${button_var}_button">${tooltip}</div>
			<ul class="mdl-menu mdl-js-menu mdl-js-ripple-effect mdl-menu--bottom-${side}" for="${button_var}_button">
"""

	item_list.each{Map item->
		Integer weight=item.font_weight ? item.font_weight : 400
		String nm=sMs(item,sNM).toLowerCase()
		html += """
				<li class="mdl-menu__item" onclick="${button_var}_itemSelected('${item.icon}',  '${nm}')">
					<div id="${nm}_icon" style="display: none;">${item.icon}</div>
					<span id="${nm}" class=" mdi mdi-${item.icon}" style="vertical-align: middle; font-weight: ${weight};"></span>
					<span>  ${item.text ? item.text : sMs(item,sNM)}</span>
				</li>
"""
	}

	html += """
			</ul>
"""
	html += """
	<script>
			function ${button_var}_itemSelected(icon, val){

				replaceIcons("${button_var}_button", icon);
				document.getElementById("${button_var}_value").textContent=val;
				document.getElementById("${button_var}_icon").textContent=icon;

			}
	</script>
"""
	return html
}

/*
static String addMenu(Map map){

	String button_var=map.var_name
	def default_val=map.default_value
	String default_icon=map.default_icon
	List<Map> item_list=map.list
	String tooltip=map.tooltip ? map.tooltip : ""
	String title=map[sTIT]

	String html="""
		<div>
		<div id="${button_var}_value" style="display: none;">${default_val}</div>
		<div id="${button_var}_icon" style="display: none;">mdi-${default_icon}</div>
		<span>
			<button id="${button_var}_button" class="mdl-button mdl-js-button mdl-js-ripple-effect" tabindex="-1">
				<i id="${button_var}_icon_display" class="mdi mdi-${default_icon}">
					<label id="${button_var}_text_display"> ${title}</label>
				</i>

			</button>
			<div class="mdl-tooltip" for="${button_var}_button">${tooltip}</div>
		</span>

		<ul class="mdl-menu mdl-js-menu mdl-js-ripple-effect" for="${button_var}_button"  style="overflow-y: scroll; max-height: 50vh; line-height: 10px;"> """

	item_list.each{item->
		Integer weight=item.font_weight ? item.font_weight : 400
		html += """
			<li id="${item.var}_list_main" class="mdl-menu__item" onclick="${button_var}_itemSelected('${item.icon}',  '${item.var}')">
				<div id="${item.var}_list_item" style="display: none;">${item.icon}</div>
				<span id="${item.var}_list_title" class=" mdi mdi-${item.icon}" style="vertical-align: middle; font-weight: ${weight};"></span>
				<span id="${item.var}_list_name">${item.text ? item.text : item.name}</span>
			</li>"""
	}

	html += """
		</ul></div>
	"""

	html += """
	<script>
			function ${button_var}_itemSelected(icon, val){
				let currentIcon=document.getElementById("${button_var}_icon").textContent;
				let iconDisplay=jQuery("#${button_var}_icon_display");
				console.log(iconDisplay.hasClass("mdi"));
				iconDisplay.removeClass(currentIcon);
				iconDisplay.addClass(icon);
				document.getElementById("${button_var}_text_display").textContent=document.getElementById(val+"_list_name").textContent;
				document.getElementById("${button_var}_value").textContent=val;
				document.getElementById("${button_var}_icon").textContent=icon;

			}
		</script>
	"""
	return html
}
*/
static String addIconMenu(Map map){

	String button_var=sMs(map,'var_name')
	def default_val=map.default_value
	String default_icon=sMs(map,'default_icon')
	List<Map> item_list=map.list
	String tooltip
	tooltip=sMs(map,'tooltip') ?: ""
	Boolean description=map.description ? map.description : false
	def width=map.width
	if(sMs(map,'tooltip') == "Use Icon Name") tooltip="No Icon Selected"

	String html
	html="""
		<div>
		<div id="${button_var}_menu" class="flex-item" style="flex-grow:1;" tabindex="-1; ">
		<div id="${button_var}_value" style="display: none;">${default_val}</div>
		<div id="${button_var}_icon" style="display: none;">${default_icon}</div>
		<div>
		<button id="${button_var}_button"
			class="mdl-button mdl-js-button mdl-button--icon mdi mdi-${default_icon}">
		</button>
"""
	if(description)
		html += """ <span> <b>Icon</b> </span><span id= "${button_var}_text">(None)</span>
"""
	html += """ </div>

	<div id="${button_var}_tooltip" class="mdl-tooltip" for="${button_var}_menu">${tooltip}</div>
	<ul class="mdl-menu mdl-js-menu mdl-js-ripple-effect" for="${button_var}_menu" style="max-height: 40vh; overflow-y: scroll !important;">
"""

	Integer count
	count=0
	item_list.each{Map item->
		if(count % width == 0){
			html+="""<div class="flex-container">
"""
		}
		Integer weight=item.font_weight ? item.font_weight : 400
		String icon_var=sMs(item,sICON).replaceAll("-","_")
		html += """ <div class="flex-item" style="flex-grow:1;">
					<li class="mdl-menu__item" onclick="${button_var}_itemSelected('${item.icon}',  '${sMs(item,sNM).toLowerCase()}', '${sMs(item,sNM)}')">
						<div id="${button_var}_${icon_var}_icon" style="display: none;">${item.icon}</div>
						<span id="${button_var}_${icon_var}" class=" mdi mdi-${item.icon}" style="vertical-align: middle; font-size: 5vw;"></span>
						<div id="${button_var}_${icon_var}_text" class="mdl-tooltip" for="${button_var}_${icon_var}">${sMs(item,sNM)}</div>
					</li>
					</div>
"""
		if(count % width == width-1){
			html+= """</div>
"""
		}
		count++
	}

	html += """</ul>
	</div>
	</div>
"""
	html += """
	<script>
			function ${button_var}_itemSelected(icon, val, name){
				replaceIcons("${button_var}_button", icon);
				document.getElementById("${button_var}_value").textContent=val;
				document.getElementById("${button_var}_icon").textContent=icon;
"""
	if(description)
		html += """ document.getElementById("${button_var}_text").textContent="("+name+")";"""
	if(map.tooltip == "Use Icon Name")
		html += """ document.getElementById("${button_var}_tooltip").textContent="Selected Icon: "+name;"""

	html += """
			}
		</script>

"""
	return html
}

static String addSlider(Map map){

	String var=sMs(map,'var')
	String title=sMs(map,sTIT)
	Integer min=iMs(map,'min')
	Integer max=iMs(map,'max')
	Integer value=iMs(map,sVAL)

	String html="""
	<div id="${var}_box" class="flex-container">
		<div class="flex-item" style="flex-basis: 35%;">
			<label for="${var}_slider">${title}</label>
		</div>
		<div class="flex-item" style="flex-grow: auto;">
			<input id="${var}_slider" class="mdl-slider mdl-js-slider" type="range" min="${min}" max="${max}" value="${value}"
				tabindex="0" oninput="${var}_showMessage(this.value)" onchange="${var}_showMessage(this.value)">
		</div>
		<div class="flex-item" style="flex-basis: 15%;">
			<div id="${var}_message">0%</div>
		</div>
	</div>

	<script language="javascript">
		function ${var}_showMessage(value){
			document.getElementById("${var}_message").innerHTML=value + "%";
		}
	</script>
"""
	return html
}

static String defineHTML_CSS(){

	String html="""

<style>
.grid-stack{
  background: #000000;
}

.grid-stack-item-content{
  color: #2c3e50;
  text-align: center;
  background-color: #18bc9c;
  left: 1px !important;
  right: 1px !important;
}

.grid-stack-item-content{overflow:hidden !important}

/* Optional styles for demos */
.btn-primary{
  color: #fff;
  background-color: #007bff;
}

.btn{
  display: inline-block;
  padding: .375rem .75rem;
  line-height: 1.5;
  border-radius: .25rem;
}

.font-test{
	line-height: 10vw;
	padding-top: 0px !important;
	font-size: 10vw;
	margin: 0 !important;
	text-align: center;
}

a{
  text-decoration: none;
}

h1{
  font-size: 2.5rem;
  margin-bottom: .5rem;
}

.placeholder-content{
	left: 0;
	right: 0;
}

.flex-container{
  display: flex;
  flex-wrap: nowrap;
  width: 100%;
  background-color: rgba(0,0,0,0);
}

.flex-container > div{
  background-color: rgba(0,0,0,0);
  width: auto;
  margin: 2px;
  text-align: center;
  line-height: 3vh;
  font-size: 3vh;
}

.border-container{
	border-style: solid none none none;
	padding-bottom: 1vh;
	padding-top: 1vh;
	width: 100%;
}

.mdl-textfield__label{
   margin-bottom:0px !important;
   margin-top:0px !important;
}

</style>

"""
	return html
}

static String defineSelectBox(Map map){

	String title=map[sTIT]
	String var=sMs(map,'var')
	Map<String,Map> list=map.list
	String visible=map.visible == false ? """style="display: none;" """ : ""
	String function=map.function

	String html
	html="""

	<div id=${var}_main class="mdl-textfield mdl-js-textfield mdl-textfield--floating-label" ${visible}>
			<select class="mdl-textfield__input" id="${var}" name="${var}" style="line-height: 5vh !important" onchange="${function}(this.value)">
			<option value="blank"></option>
"""
	list.each{key, item->
		html+="""<option value="${key}">${sMs(item,sNM)}</option>"""
	}
	html+=
			"""
			</select>
			<label class="mdl-textfield__label"  for="${var}">${title}</label>
			</div>
"""

	return html
}

String defineNewTileDialog(){

	TreeMap<String,TreeMap> typeList=(TreeMap<String,TreeMap>)state.newTileDialog

	String html
	html=""
	html += """
				<dialog id="addTileDialog" class="mdl-dialog mdl-shadow--12dp" tabindex="-1" style="background-color: rgba(255, 255, 255, 0.90); border-radius: 2vh; height: 95vh; visibility: none;">
				   <div class="mdl-dialog__content">
					  <div class="mdl-layout">
						  <div id="options_title" class="mdl-layout__title" style="color: black; text-align: center;">
							New Tile Options
						  </div>

						 <div class="mdl-grid" style="width: 100%">
							<div class="border-container">
							   <div id="menu_items" class="flex-container">
								   <div class="flex-item" style="max-width:18%; flex-basis: 18%" tabindex="-1">
									  <button id="save_button" type="button" class="mdl-button mdi mdi-content-save" onclick="addNewTileClose()" style="color: darkgreen; font-size: 4vh !important;"></button>
										 <div class="mdl-tooltip" for="save_button" style="background-color: rgba(255,255,255,0.75); color: rgba(0,0,0,100);)">Save/Close</div>
								   </div>
								   <div class="flex-item" style="max-width:18%; flex-basis: 18% padding-bottom: 0 !important;" tabindex="-1">
									   <button id="close_button" type="button" class="mdl-button mdi mdi-close-circle" onclick="closeAddTileWindow()" style="color: darkred; font-size: 4vh !important;"></button>
										  <div class="mdl-tooltip" for="close_button" style="background-color: rgba(255,255,255,0.75); color: rgba(0,0,0,100);)">Exit/Don't Save</div>
								   </div>
							   </div>
							</div>

						<div class="border-container">
								<div id="menu_items" class="flex-container">
									<div class="flex-item" style="max-width:50%; flex-basis: 50%" tabindex="-1">
"""

	TreeMap list
	list=new TreeMap(typeList.main_list)
	html+= defineSelectBox((sTIT): "Title Span", var: "new_tile_span", list: list, function: "selectTileSpan")

	html += """
									</div>
								</div>
"""
	html += """
								<div id="menu_items" class="flex-container">
									<div class="flex-item" style="max-width:75%; flex-basis: 75%" tabindex="-1">
"""

	spanFLD.each{String span_key, Map span->
		list=new TreeMap( (TreeMap)((TreeMap)typeList[span_key]).measurement_list)
		//if(list!=[:])
		if(list)
			html+= defineSelectBox((sTIT): span[sTIT], var: span_key+"_measurement", list: list, visible: false, function: "selectTileType")
	}

	html += """
									</div>
								</div>
"""
	html += """
								<div id="menu_items" class="flex-container">
									<div class="flex-item" style="max-width:75%; flex-basis: 90%" tabindex="-1">
"""

	spanFLD.each{String span_key, Map span->
		TreeMap tl= (TreeMap)typeList[span_key]
		if(tl[sTIT]){
			list=new TreeMap((TreeMap)tl.time_list)
			html+= defineSelectBox((sTIT): tl[sTIT], var: span_key+"_time", list: list, visible: false, function: "selectTileTime")
		}
	}
	/*
	html+= defineSelectBox((sTIT): "Days to Display", var: "daily_time", list: daily_list,   visible: false, function: "selectTileTime");
	html+= defineSelectBox((sTIT): "Hours to Display", var: "hourly_time", list: hourly_list,  visible: false, function: "selectTileTime");
	*/
	html += """
									</div>
								</div>
"""

	html+= """</div>
"""

	html += """</div></div></dialog>
"""

	return html
}

String defineTileDialog(){

	/*
	List<Map> list=[]
	for(Map item in (List<Map>)state.tile_settings){
		list << [(sNM): item[sTIT], (sICON): item[sICON], var: item.var]
	} */

	String html
	html="""
		<dialog id="tileOptions" class="mdl-dialog mdl-shadow--12dp" tabindex="-1" style="background-color: rgba(255, 255, 255, 0.90); border-radius: 2vh; height: 95vh; visibility: none;">
			<div class="mdl-dialog__content">

				<div class="mdl-layout">
					<div id="options_title" class="mdl-layout__title" style="color: black; text-align: center;">
						Options
					</div>

					<div class="mdl-grid" style="width: 100%">
					<div class="border-container">
					<div id="text_box" class="flex-container">
					<div class="flex-item" style="max-width:15%; flex-basis: 15%;" tabindex="-1">
						<button id="trash_button" type="button" class="mdl-button mdi mdi-trash-can-outline" onclick="deleteTile()" style="color: darkred; font-size: 4vh !important;"></button>
						<div class="mdl-tooltip" for="trash_button" style="background-color: rgba(255,255,255,0.75); color: rgba(0,0,0,100);)">Delete this tile</div>
					</div>

					<div class="flex-item" style="max-width:15%; flex-basis: 15%" tabindex="-1">
						<button id="new_tile" type="button" class="mdl-button mdi mdi-shape-rectangle-plus"" onclick="newTile()" style="color: darkgreen; font-size: 4vh !important;"></button>
						<div class="mdl-tooltip" for="new_tile" style="background-color: rgba(255,255,255,0.75); color: rgba(0,0,0,100);)">Place New Tile</div>
					</div>
					<div class="flex-item" style="max-width:15%; flex-basis: 15%" tabindex="-1">
						<button id="save_button" type="button" class="mdl-button mdi mdi-content-save" onclick="saveWindow()" style="color: darkgreen; font-size: 4vh !important;"></button>
						<div class="mdl-tooltip" for="save_button" style="background-color: rgba(255,255,255,0.75); color: rgba(0,0,0,100);)">Save/Close</div>
					</div>
					<div class="flex-item" style="max-width:15%; flex-basis: 15%" tabindex="-1">
						<button id="save_all_button" type="button" class="mdl-button mdi mdi-content-save-all" onclick="saveAllWindow()" style="color: darkgreen; font-size: 4vh !important;"></button>
						<div class="mdl-tooltip" for="save_all_button" style="background-color: rgba(255,255,255,0.75); color: rgba(0,0,0,100);)">Save Colors and Opacity to All Tiles</div>
					</div>
					<div class="flex-item" style="max-width:15%; flex-basis: 15% padding-bottom: 0 !important;" tabindex="-1">
						<button id="close_button" type="button" class="mdl-button mdi mdi-close-circle" onclick="closeWindow()" style="color: darkred; font-size: 4vh !important;"></button>
						<div class="mdl-tooltip" for="close_button" style="background-color: rgba(255,255,255,0.75); color: rgba(0,0,0,100);)">Exit/Don't Save</div>
					</div>
					<div class="flex-item" style="max-width:15%; flex-basis: 15% padding-bottom: 0 !important;" tabindex="-1">
						<button id="update_button" type="button" class="mdl-button mdi mdi-cloud-refresh" onclick="getWeatherData()" style="color: darkgreen; font-size: 4vh !important;"></button>
						<div class="mdl-tooltip" for="update_button" style="background-color: rgba(255,255,255,0.75); color: rgba(0,0,0,100);)"><b>Refresh View</b><br>This may take some time, depending on the number of tiles</div>
					</div>
				</div>
			</div>
"""

//ALIGNMENT
	html+= """
<!-- ALIGNMENT -->
<div class="border-container">
	<div id="text_box" class="flex-container">
		<div class="flex-item" style="flex-grow:1;" tabindex="-1">
"""

	html +=  addIconMenu(var_name: "selected_icon",(sTIT): "Select Tile Type", default_icon: "alpha-x-circle-outline",
			default_value: "center", tooltip: "Use Icon Name", list: getIconList(), width: 4)

	html += """
			</div>
			<div class="flex-item" style="flex-grow:1;" tabindex="-1">
"""
	html+= addButtonMenu(var_name: "horizontal_alignment", default_icon: "format-align-center", tooltip: "Horizontal Alignment", default_value: "center", side: "left",
			list:[[(sNM): "Left",   (sICON): "format-align-left"],
				  [(sNM): "Center", (sICON): "format-align-center"],
				  [(sNM): "Right",  (sICON): "format-align-right"]])

	html+= """
			</div>
			<div class="flex-item" style="flex-grow:1;" tabindex="-1">
"""

	html+= addButtonMenu(var_name: "icon_spacing", default_icon: "keyboard-space", tooltip: "Icon Spacing", default_value: "Single Space", side: "left",
			list:[[(sNM): "No Space",	(sICON): "arrow-collapse-horizontal"],
				  [(sNM): "Single Space", (sICON): "keyboard-space"],
				  [(sNM): "Double Space", (sICON): "arrow-expand-horizontal"]])

	html+= """
			</div>
			<div class="flex-item" style="flex-grow:1;" tabindex="-1">
"""

	html+= addButtonMenu(var_name: "decimal_places", default_icon: "decimal", tooltip: "Decimal Places", default_value: "One Decimal",  side: "left",
			list:[[(sNM): "No Decimal",	(sICON): "hexadecimal"],
				[(sNM): "One Decimal",	(sICON): "surround-sound-2-0"],
				[(sNM): "Two Decimals",   (sICON): "decimal"]])

	html += """
			</div>
			<div class="flex-item" style="flex-grow:1;" tabindex="-1">
"""

	html+= addButtonMenu(var_name: "font_weight", default_icon: "numeric-4-circle", default_value: "center", tooltip: "Font Weight", side: "right",
			list:[[(sNM): "Thin",   (sICON): "numeric-1-circle"],
				[(sNM): "Normal", (sICON): "numeric-4-circle"],
				[(sNM): "Bold",   (sICON): "numeric-7-circle"],
				[(sNM): "Thick",  (sICON): "numeric-9-circle"]])
	html += """
			</div>
			<div class="flex-item" style="flex-grow:1;" tabindex="-1">
"""

	html+= addButtonMenu(var_name: "units_spacing", default_icon: "keyboard-space", tooltip: "Units Spacing", default_value: "Single Space", side: "right",
			list:[[(sNM): "No Space",	(sICON): "arrow-collapse-horizontal"],
				[(sNM): "Single Space", (sICON): "keyboard-space"],
				[(sNM): "Double Space", (sICON): "arrow-expand-horizontal"]])

	html +=  """
			</div>
		</div>
	</div>
"""

//TEXT COLOR

	html+= addColorPicker(var: "text",(sTIT): "Text")

//BACKGROUND COLOR

	html+= addColorPicker(var: "background",(sTIT): "Background")

//Font Adjustment
	html += """
	<div class="border-container">
		<div id="text_box" class="flex-container">
"""

	html+= addSlider(var: "font_adjustment",(sTIT): "Relative Size", min: -100, (sVAL): 0, max:100)

	html+="""
		</div>
	</div>
	<div class="border-container">
			<!-- CUSTOM TEXT -->
			<div class="flex-item" style="flex-grow:auto;" tabindex="-1">
				<div class="mdl-textfield mdl-js-textfield mdl-textfield--floating-label">
					<input class="mdl-textfield__input" type="text" id="tileText">
					<label class="mdl-textfield__label" for="tileText">Static Text</label>
				</div>
			</div>
		</div>
		</div></div></div>
</dialog>
"""
	return html
}

/*
String getTileListItem(Map map){
	String function=map.function
	String var= sMs(map,sNM)
	Map menu=map.list
	List selections=map.selections.clone()
	selections << var

	return ""

 */
/*	String onclick
	if(!menu.list) onclick="""onclick="${map.function}('${selections}')" """

	String html="""<span id=${var}_menu ${onclick}>"""
	if(menu.icon){
			html += """<button id="${var}_button" class="mdl-button mdl-js-button mdl-js-ripple-effect" tabindex="-1">
					<i id="${var}_icon_display" class="mdi mdi-${default_icon}"  style="color: darkgreen; font-size: 6vh !important;"></i>
					</button>
					"""
	}
	if(menu.text){
			html += """<span id=${var}_text>${text}</span> """
	}
	if(menu.tooltip){
			html += """<div class="mdl-tooltip" for="${button_var}_button">${tooltip}</div>"""
	}
	html += """</span>"""

	if(menu.list){
			html += """<ul class="mdl-menu mdl-js-menu mdl-js-ripple-effect" for="${var}_menu" style="overflow-y: scroll; max-height: 50vh; line-height: 10px;"> """

			menu.list.each{item->
				html+= getTileListItem(name: var+"_"+item.name, list: item.list, selections: selections, function: function)
			}

			html += """</ul>"""
	} else{
	/   var=item.name.replaceAll(" ","")
		var=parent_+var
		List select=selections.clone()
		select << [item.name]

		func="""onclick="${function_name}('${select}')" """;
		if(item.list){
			html += getTileListItem(name: item.name, parent: var, function: function, list: item.list, selections: select);
		}
		else{
			html += """ <li id="${var}_list_main" class="mdl-menu__item" ${func}>
							<span id="${var}_list_name">${item.name}</span>
						</li>"""
		}

	}

	html += """</ul>"""
	return html
	*/
//}

String defineHTML_Tile(Boolean locked){

/*
	String temp_units='°'
	String rain_units='"'
	String m_time_units=' am'
	String e_time_units=' pm'
	String wind_units=' mph'
	String pressure_units='inHg'

	if(tile_units == "metric"){
		rain_units='mm'
		m_time_units=''
		e_time_units=''
		wind_units=' m/sec'
		pressure_units='mmHg'
	}
*/
	String background
	background='black'
	if(background_color != null){
		Float transparent=background_color_transparent ? 0.0 : background_opacity
		background=getRGBA(background_color, transparent)
	}

	String html_
	html_="""
<style type="text/css">
	.grid-stack-item-removing{
		opacity: 0.8;
		filter: blur(5px);
	}
"""

	html_ += """
	#trash{
		background: rgba(0, 0, 0, 0);
	}

</style>
"""

	html_ += """
	<body style="background-color:${background}; overflow: visible;">

	<div class="flex-container" style="display: none;">

		<div id="trash" class="flex-item" style="flex-grow:1;">
				<span id="trash" class="text-center mdi mdi-trash-can-outline" style="color: rgba(255, 50, 50, 0.75); background-color: rgba(0,0,0,100); font-size: 10vh; line-height: 15vh"></span>
		</div>
		<div class="mdl-tooltip" for="trash" style="background-color: rgba(255,255,255,0.75); color: rgba(0,0,0,100);)">
			<div>Drag a TILE to</div>
			<div class="mdi mdi-trash-can-outline" style="font-size: 5vh"></div>
			<div>to REMOVE it</div>
		</div>

		<div style="flex-grow: 6;"></div>

		<div class="mdl-tooltip" for="add_tile" style="background-color: rgba(255,255,255,0.75); color: rgba(0,0,0,100);)">
			<div>CLICK to ADD a TILE</div>
		</div>
	</div>

	<div class="grid-stack grid-stack-26" data-gs-animate="yes" data-gs-verticalMargin="1" data-gs-column="26" id="main_grid">
"""

	//Main Tile Building Code
	((List<Map>)state.tile_settings).eachWithIndex{Map item, index->
		html_ += getTileHTML(item, locked)
	}

	html_ += """
	</div>
	</div>
	</div>
	"""

	html_ += """
<style>
	.mdl-layout__title{
		padding-bottom: 20px;
		background: transparent;
	}

	.mdl-grid__hubitat{
		padding: 0px !important;
		margin: 5px !important;
	}

	.mdl-dialog__content{
		padding: 0px !important;
		margin: 5px !important;
	}

	.mdl-dialog{
		width: 75vw !important;
	}

	.is-checked{}
</style>

"""

	return html_

}

/*
String defineHTML_globalVariables(){
	String html="""
		var sunrise;
		var sunset;
		let options=[];
		let pws_data=[];
		let currentTemperature;
	"""
} */

/*
//tile_settings_HTML
String defineUpdateDataHTML(String var){

	//TODO
	if(!settings["$var"]){
		//if(!settings["$var"]){ wremoveSetting(var.toString()) }
		app.updateSetting("${var}", [(sVAL): sBLK, (sTYPE): "string"])
	}

	String html="""
				<input type="text" id="settings${var}" name="settings[${var}]" value="${settings[var]}" style="display: none;" >
				<div class="form-group">
					<input type="hidden" name="${var}.type" value="text" submitOnChange>
					<input type="hidden" name="${var}.multiple" value="false">
				</div>
			"""

	return html
}
*/

static String defineScript(){
	String html="""
	<script type="text/javascript">


	</script>
"""
	return html
}

String getWeatherTile_weather2(Boolean config){
	String fullSizeStyle="margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden"

	buildWeatherData()

	String html
	html=defineHTML_Header()
	html += """<head>
			<meta name="viewport" content="width=device-width, initial-scale=1.0"><style>"""
	//CSS
	html += defineHTML_CSS()
	html += """</head>
<body onload="initializeWeather()">
"""
	html += defineHTML_Tile(config)
	if(config) html += defineTileDialog()
	if(config) html += defineNewTileDialog()

	html += defineScript()

	html+="</body></html>"

	return html
}



//oauth endpoints
def getTile_weather2(){
	return wrender(contentType: "text/html", data: getWeatherTile_weather2(false))
}

String getGraph_weather2(){
	return getWeatherTile_weather2(true)
	//return wrender(contentType: "text/html", data: getWeatherTile_weather2(true))
}

String getData_weather2(){
	buildWeatherData()
	return JsonOutput.toJson((List)state.tile_settings)
}


def updateSettings_weather2(){

	state.tile_settings=request.JSON
	//atomicState.temp_tile_settings=request.JSON

	return wrender(contentType: "application/json", data: """{"status":"success"}""")
}








/*
 * TODO: Forecast methods
 */

//@Field static List<Map> unitTemp
//@Field static List<Map> unitWind
@Field static List<Map> unitPrecip
@Field static List<Map> unitDate
//@Field static List<Map> unitTime
//@Field static List<Map> unitPercent
@Field static List<Map> selectionsF
@Field static Integer rowsF
@Field static Integer columnsF

void initFields(){
	if(!unitPrecip){
		unitPrecip=[["millimeters": "Millimeters (mm)"], ["inches": """Inches (") """]]
//		unitPercent=[["percent_numeric": "Numeric (0 to 100)"], ["percent_decimal": "Decimal (0.0 to 1.0)"]]
//		unitTemp=[[sFAHR: "Fahrenheit (°F)"], [sCELS: "Celsius (°C)"], ["kelvin": "Kelvin (K)"]]
//		unitWind=[["meters_per_second": "Meters per Second (m/s)"], ["miles_per_hour": "Miles per Hour (mph)"], ["knots": "Knots (kn)"], ["kilometers_per_hour": "Kilometers per Hour (km/h)"]]
//		unitTime=[["time_seconds": "Seconds since 1970"], ["time_milliseconds": "Milliseconds since 1970"], ["time_twelve": "12 Hour (2:30 PM)"], ["time_two_four": "24 Hour (14:30)"]]
		unitDate=[["day_only": "Day Only (Thursday)"], ["date_only": "Date Only (29)"], ["day_date": "Day and Date (Thursday 29)"], ["month_day": "Month and Day (June 29)"]]

		selectionsF=[
				[(sTIT): 'Weather Forecast Icon', var: "weather_icon", ow: "weather.0.description", iu: sNONE, (sICON): sNONE, icon_loc: sNONE, icon_space: sBLK, h: 4, w: 4, baseline_row: 2, baseline_column: 1, alignment: "center", lpad: 0, rpad: 0, unit: sNONE, decimal: "no", font: 20, font_weight: "400", imperial: sNONE, metric: sNONE],
				[(sTIT): 'Forecast Description', var: "description", ow: "weather.0.description", iu: sNONE, (sICON): sNONE, icon_loc: sNONE, icon_space: sBLK, h: 2, w: 4, baseline_row: 6, baseline_column: 1, alignment: "center", lpad: 0, rpad: 0, unit: sNONE, decimal: "no", font: 10, font_weight: "400", imperial: sNONE, metric: sNONE],
				[(sTIT): 'Forecast Temperature', var: "temperature", ow: "temp.day", iu: sFAHR, (sICON): sNONE, icon_loc: sNONE, icon_space: sBLK, h: 4, w: 2, baseline_row: 8, baseline_column: 1, alignment: "right", lpad: 0, rpad: 0, unit: unitTemp, decimal: "yes", font: 20, font_weight: "900", imperial: sFAHR, metric: sCELS],
				[(sTIT): 'Forecast High', var: "high", ow: "temp.max", iu: sFAHR, (sICON): "mdi-arrow-up-thick", icon_loc: "right", icon_space: sBLK, h: 2, w: 2, baseline_row: 8, baseline_column: 3, alignment: "left", lpad: 3, rpad: 0, unit: unitTemp, decimal: "yes", font: 7, font_weight: "700", imperial: sFAHR, metric: sCELS],
				[(sTIT): 'Forecast Low', var: "low", ow: "temp.min", iu: sFAHR, (sICON): "mdi-arrow-down-thick", icon_loc: "right", icon_space: sBLK, h: 2, w: 2, baseline_row: 10, baseline_column: 3, alignment: "left", lpad: 3, rpad: 0, unit: unitTemp, decimal: "yes", font: 7, font_weight: "700", imperial: sFAHR, metric: sCELS],
				[(sTIT): 'Precipitation Forecast', var: "precipitation", ow: "rain", iu: "millimeters", (sICON): "mdi-umbrella-outline", icon_loc: "left", icon_space: sSPC, h: 1, w: 2, baseline_row: 12, baseline_column: 1, alignment: "right", lpad: 0, rpad: 3, unit: unitPrecip, decimal: "yes", font: 4, font_weight: "400", imperial: "inches", metric: "millimeters"],
				[(sTIT): 'Precipitation Forecast Percent', var: "precipitation_percent", ow: "pop", iu: "percent_decimal", (sICON): sNONE, icon_loc: sNONE, icon_space: sSPC, h: 1, w: 2, baseline_row: 12, baseline_column: 3, alignment: "left", lpad: 3, rpad: 0, unit: unitPercent, decimal: "yes", font: 4, font_weight: "400", imperial: "percent_numeric", metric: "percent_numeric"],
				[(sTIT): 'Sunrise', var: "sunrise", ow: "sunrise", iu: "time_seconds", (sICON): "mdi-weather-sunset-up", icon_loc: "left", icon_space: sSPC, h: 1, w: 2, baseline_row: 13, baseline_column: 1, alignment: "right", lpad: 0, rpad: 3, unit: unitTime, decimal: "no", font: 4, font_weight: "400", imperial: "time_twelve", metric: "time_two_four"],
				[(sTIT): 'Sunrise Temp', var: "sunrise_temp", ow: "temp.morn", iu: sFAHR, (sICON): sNONE, icon_loc: sNONE, icon_space: sSPC, h: 1, w: 1, baseline_row: 13, baseline_column: 3, alignment: "left", lpad: 3, rpad: 0, unit: unitTemp, decimal: "yes", font: 4, font_weight: "400", imperial: sFAHR, metric: sCELS],
				[(sTIT): 'Sunset', var: "sunset", ow: "sunset", iu: "time_seconds", (sICON): "mdi-weather-sunset-down", icon_loc: "left", icon_space: sSPC, h: 1, w: 2, baseline_row: 14, baseline_column: 1, alignment: "right", lpad: 0, rpad: 3, unit: unitTime, decimal: "no", font: 4, font_weight: "400", imperial: "time_twelve", metric: "time_two_four"],
				[(sTIT): 'Sunset Temp', var: "sunset_temp", ow: "temp.eve", iu: sFAHR, (sICON): sNONE, icon_loc: sNONE, icon_space: sSPC, h: 1, w: 1, baseline_row: 14, baseline_column: 3, alignment: "left", lpad: 3, rpad: 0, unit: unitTemp, decimal: "yes", font: 4, font_weight: "400", imperial: sFAHR, metric: sCELS],
				[(sTIT): 'Dewpoint', var: "dewpoint", ow: "dew_point", iu: sFAHR, (sICON): "mdi-waves", icon_loc: "left", icon_space: sSPC, h: 1, w: 2, baseline_row: 15, baseline_column: 1, alignment: "right", lpad: 0, rpad: 3, unit: unitTemp, decimal: "yes", font: 4, font_weight: "400", imperial: sFAHR, metric: sCELS],
				[(sTIT): 'Dewpoint Description', var: "dewpoint_desc", ow: "dew_point", iu: sNONE, (sICON): sNONE, icon_loc: sNONE, icon_space: sSPC, h: 1, w: 2, baseline_row: 15, baseline_column: 3, alignment: "left", lpad: 3, rpad: 0, unit: unitTemp, decimal: "no", font: 4, font_weight: "400", imperial: sNONE, metric: sNONE],
				[(sTIT): 'Forecast Wind', var: "wind", ow: "wind_speed", iu: "miles_per_hour", (sICON): "mdi-tailwind", icon_loc: "left", icon_space: sSPC, h: 1, w: 2, baseline_row: 16, baseline_column: 1, alignment: "right", lpad: 0, rpad: 3, unit: unitWind, decimal: "yes", font: 4, font_weight: "400", imperial: "miles_per_hour", metric: "meters_per_second"],
				[(sTIT): 'Forecast Clouds', var: "clouds", ow: "clouds", iu: "percent_numeric", (sICON): "mdi-cloud-outline", icon_loc: "right", icon_space: sSPC, h: 1, w: 2, baseline_row: 16, baseline_column: 3, alignment: "left", lpad: 3, rpad: 0, unit: unitPercent, decimal: "no", font: 4, font_weight: "400", imperial: "percent_numeric", metric: "percent_numeric"],
				[(sTIT): 'Day and Date', var: "date", ow: "dt", iu: "time_seconds", (sICON): sNONE, icon_loc: sNONE, icon_space: sSPC, h: 2, w: 4, baseline_row: 18, baseline_column: 1, alignment: "center", lpad: 0, rpad: 0, unit: unitDate, decimal: "no", font: 8, font_weight: "800", imperial: "day_date", metric: "day_date"],
		]
		rowsF=19
		columnsF=4
	}

}

def tileForecast(){

	List<Map> updateEnum=[["60000":"1 Minute"],["300000":"5 Minutes"], ["600000":"10 Minutes"], ["1200000":"20 Minutes"], ["1800000":"Half Hour"],
							["3600000":"1 Hour"], ["6400000":"2 Hours"], ["19200000":"6 Hours"], ["43200000":"12 Hours"], ["86400000":"1 Day"]]

	List<Map> unitEnum =	[["imperial":"Imperial (°F, mph, in, inHg, 0:00 am)"], ["metric":"Metric (°C, m/sec, mm, mmHg, 00:00)"]]
//	List<Map> unitPressure=[["millibars": "Millibars (mbar)"], ["millimeters_mercury": "Millimeters of Mercury (mmHg)"], ["inches_mercury": "Inches of Mercury (inHg)"], ["hectopascal" : "Hectopascal (hPa)"]]
//	List<Map> unitDirection=[["degrees": "Degrees (°)"], ["radians": "Radians (°)"], ["cardinal": "Cardinal (N, NE, E, SE, etc)"]]
//	List<Map> unitTrend =	[["trend_numeric": "Numeric (↑ < 0, →=0, ↓ > 0)"], ["trend_text": "Text (↑ rising, → steady, ↓ falling)"]]

	initFields()

	dynamicPage(name: "graphSetupPage"){

		List container
		Map map=parent.openWeatherConfig()
		hubiForm_section("General Options", 1, sBLK, sBLK){
			//input( (sTYPE): sENUM, (sNM): "openweather_refresh_rate",(sTIT): "<b>Select OpenWeather Update Rate</b>", multiple: false, required: true, options: updateEnum, defaultValue: "300000")
/*			if(override_openweather){
				input( (sTYPE): sENUM, (sNM): "pws_refresh_rate",(sTIT): "<b>Select PWS Update Rate</b>", multiple: false, required: true, options: updateEnum, defaultValue: "300000")
			} */
			container=[]
			//container << hubiForm_text_input ("<b>Open Weather Map Key</b>", "tile_key", sBLK, true)

			//container << hubiForm_text_input ("<b>Latitude (Default=Hub location)</b>", "latitude", location.latitude.toString(), false)
			//container << hubiForm_text_input ("<b>Longitude (Default=Hub location)</b>", "longitude", location.longitude.toString(), false)
			if(map){
				app.updateSetting("latitude", map.latitude)
				app.updateSetting("longitude", map.longitude)
				app.updateSetting("tile_key", map.apiKey)
				String val
				switch(sMs(map,'pollInterval')){
					case '1 Minute':
						val="60000"
						break
					case '5 Minutes':
						val="300000"
						break
					case '10 Minutes':
						val="600000"
						break
					case '15 Minutes':
						val="1200000"
						break
					case '30 Minutes':
						val="1800000"
						break
					case '1 Hour':
						val="3600000"
						break
					default:
						val="10800000"
				}
				app.updateSetting("openweather_refresh_rate", val)
				container << hubiForm_text("""Using $map settings from main app """ )
				container << hubiForm_color("Background",
						"background",
						"#000000",
						false)
				container << hubiForm_slider	((sTIT): "Background Opacity",
						(sNM): "background_opacity",
						default: 90,
						min: 0,
						max: 100,
						units: "%",
						submit_on_change: false)

				container << hubiForm_switch	((sTIT): "Color Icons?", (sNM): "color_icons", default: false)

				hubiForm_container(container, 1)
				List daysEnum=[[0: "Today"], [1: "Tomorrow"], [2: "2 Days from Now"], [3: "3 Days from Now"], [4: "4 Days from Now"], [5: "Five Days from Now"]]
				input( (sTYPE): sENUM, (sNM): "day_num",(sTIT): "Day to Display", multiple: false, required: false, options: daysEnum, defaultValue: s1)
			}else{
				container << hubiForm_text("""Main app is not configured for openweather""" )
				hubiForm_container(container, 1)
			}
		}

		if(map){
			List decimalEnum =	[[0: "None (0)"], [1: "One (0.1)"], [2: "Two (0.12)"], [3: "Three (0.123)"], [4: "Four (0.1234)"]]
			selectionsF.each{Map measurement->
				String mvar=sMs(measurement,'var')
				hubiForm_section(sMs(measurement,sTIT), 1, sBLK, sBLK){
					container=[]
					container << hubiForm_switch	((sTIT): "Display "+sMs(measurement,sTIT)+"?", (sNM): mvar+"_display", default: true, submit_on_change: true)

					if((settings["${mvar}_display"]==null) || (settings["${mvar}_display"]==true)){
						container << hubiForm_fontvx_size((sTIT): mvar == "weather_icon" ? "Icon Size" : "Font Size",
								(sNM): mvar,
								default: measurement.font,
								min: 1,
								max: measurement.font*2,
								weight: ((String)measurement.font_weight).toInteger(),
								(sICON): mvar == "weather_icon")

						container << hubiForm_slider ((sTIT): "Text Weight (400=normal, 700= bold)",
								(sNM): mvar+"_font_weight",
								default: ((String)measurement.font_weight).toInteger(),
								min: 100,
								max: 900,
								units: sBLK,
								submit_on_change: false)

						container << hubiForm_color("Font", mvar, "#FFFFFF", false)
						hubiForm_container(container, 1)

						if(measurement.decimal == "yes"){
							container=[]
							container << hubiForm_switch	((sTIT): "Display Unit Values (mm, mph, mbar, °, etc)", (sNM): mvar+"_display_units", default: true, submit_on_change: false)
							hubiForm_container(container, 1)
							input( (sTYPE): sENUM, (sNM): mvar+"_decimal",(sTIT): "Decimal Places", required: false, multiple: false, options: decimalEnum, defaultValue: 1, submitOnChange: false)
						}

						String defs1=measurement.imperial
						String ts1= mvar+"_units"
						if(defs1 != sNONE){
							input( (sTYPE): sENUM, (sNM): ts1,(sTIT): "Displayed Units", required: false, multiple: false, options: measurement.unit, defaultValue: defs1, submitOnChange: false)
						}
						if(settings[ts1] == defs1) wremoveSetting(ts1)
					}else{
						hubiForm_container(container, 1)
						for( String ts1 in [ "_font", "_font_weight", "_color", "_color_transparent", "_display_units", "_decimal", "_units" ]){
							String s= mvar+ts1
							if(settings[s]!=null) wremoveSetting(s)

						}
					}
				}
			}
		}
	}
}

def mainForecast(){
	initFields()
	dynamicPage(name: "mainPage"){

		checkDup()
		List container
		if(!state.endpoint){
			hubiForm_section("Please set up OAuth API", 1, "report", sBLK){

				href( (sNM): "enableAPIPageLink",(sTIT): "Enable API", description: sBLK, page: "enableAPIPage")
			}
		}else{
			hubiForm_section("Tile Options", 1, "tune", sBLK){
				container=[]
				container << hubiForm_page_button("Configure Tile", "graphSetupPage", "100%", "poll")
				hubiForm_container(container, 1)
			}

			if(tile_key){
				local_graph_url()
				preview_tile()
			}

			put_settings()
		}
		selectionsF.each{Map measurement->
			String mvar=sMs(measurement,'var')
			if((settings["${mvar}_display"]==null) || (settings["${mvar}_display"]==true)){

				List defs=[ measurement.font, ((String)measurement.font_weight).toInteger(), "#ffffff", false, true, s1, (String)measurement.imperial ]
				Integer i
				i=0
				for( String ts1 in [ "_font", "_font_weight", "_color", "_color_transparent", "_display_units", "_decimal", "_units" ]){
					String s= mvar+ts1
					def v=settings[s]
					//log.warn "checking $s $v == ${defs[i]}"
					if(v!=null && v == defs[i]){
						wremoveSetting(s)
						//log.warn "removed $s"

					}
					i++
				}
			}
		}
	}
}

Map getOptions_forecast(){

	Map options=[
			"tile_units": tile_units,
			"display_day": day_num,
			"color_icons": color_icons,
			"openweather_refresh_rate": openweather_refresh_rate,
			"measurements": [],
	]

	initFields()

	selectionsF.each{ Map measurement->
		String var=sMs(measurement,'var')
		String outUnits=settings["${var}_units"] ? settings["${var}_units"] : ((String)measurement.imperial ?: sNONE)
		String decimals=measurement.decimal == "yes" ? (settings["${var}_decimal"] != 1 ? settings["${var}_decimal"] :1): sNONE

		(List)options.measurements << [ "name": var,
										"openweather": measurement.ow,
										"in_unit" : measurement.iu,
										"out_unit" : outUnits,
										"decimals" : decimals,
		]
	}

	return options
}

String defineHTML_Header_forecast(){
	String html="""
	<link rel="stylesheet" href="https://fonts.googleapis.com/icon?family=Material+Icons">
	<link rel="stylesheet" href="https://code.getmdl.io/1.3.0/material.indigo-pink.min.css">
	<link rel="stylesheet" href="//cdn.materialdesignicons.com/5.4.55/css/materialdesignicons.min.css">

	<script>
		const localURL =		"${state.localEndpointURL}";
		const secretEndpoint= "${state.endpointSecret}";
		const latitude =		"${latitude}";
		const longitude =		"${longitude}";
		const tile_key =		"${tile_key}";
	</script>

	<script src="https://code.getmdl.io/1.3.0/material.min.js"></script>
	<!--script defer src="http://192.168.1.64:8080/WeatherTile.js"></script> -->
	<script defer src="/local/${isSystemType() ? 'webcore/' : ''}a7af9806-4b0e-4032-a78e-a41e27e4d685-WeatherTile.js"></script>
	<script src="https://ajax.googleapis.com/ajax/libs/jquery/3.4.0/jquery.min.js"></script>
	<script type="text/javascript" src="https://www.google.com/jsapi"></script>
	<script type="text/javascript" src="https://www.gstatic.com/charts/loader.js"></script>

"""
	return html
}

String defineHTML_CSS_forecast(){

	initFields()

	Integer num_columns=columnsF
	def column_width=100.0/num_columns

	Integer num_rows=rowsF
	def row_height=100.0/num_rows

/*	String background='black'
	if(background_color != null){
		Float transparent=background_color_transparent ? 0.0 : background_opacity
		background=getRGBA(background_color, transparent)
	} */

	String html
	html="""
	.grid-container{
		display: grid;
"""
	html += "		grid-template-columns:"
	Integer i
	for(i=0; i<num_columns; i++)
		html+="${column_width}vw "
	html += ";"
	html +="		grid-template-rows: "
	html +="${row_height/2}vh "

	for(i=0; i<num_rows-1; i++)
		html+="${row_height}vh "

	html+="${row_height/2}vh;"
	html+= """
		grid-gap: 0px;
		align-items: center;
		background-color: ${getRGBA(background_color, background_opacity)};
	}

	.grid-container > div{
		text-align: center;
	}
"""

	//current_row=2 //leave top row blank
	selectionsF.each{Map item->
		String var=sMs(item,'var')
		if(settings["${var}_display"]){
			String font=settings["${var}_font"] ?: item.font
			def weight=settings["${var}_font_weight"] ?: item.font_weight
			String color=settings["${var}_color"] ?: "#FFFFFF"
			def row_start=item.baseline_row
			def row_end=item.baseline_row + item.h
			def column_start=item.baseline_column
			def column_end=item.baseline_column + item.w
			html += """
	.${var}{
		grid-row-start: ${row_start};
		grid-row-end: ${row_end};
		grid-column-start: ${column_start};
		grid-column-end: ${column_end};
		font-size: ${font}vh;
		padding-top: 0vmin !important;
		padding-left:  ${item.lpad}vw !important;
		padding-right: ${item.rpad}vw !important;
		text-align: ${item.alignment} !important;
		color: ${color} !important;
		font-weight: ${weight};
	}
"""
		}
	}
	return html
}



String defineHTML_Tile_forecast(){

/*
	def temp_units='°'
	def rain_units='"'
	def m_time_units=' am'
	def e_time_units=' pm'
	def wind_units=' mph'
	def pressure_units='inHg'

	if(tile_units == "metric"){
		rain_units='mm'
		m_time_units=''
		e_time_units=''
		wind_units=' m/sec'
		pressure_units='mmHg'
	} */

	initFields()

	String html
	html="""
	<div class="grid-container">
	"""
	selectionsF.each{Map item->
		String var=sMs(item,'var')
		html += """<div class="${var}">"""

		//Left Icon
		if(item.icon != sNONE && item.icon_loc == "left"){
			//log.debug(item.icon)
			html+="""<span class="mdi ${item.icon}">${item.icon_space}</span>"""
		}

		//Main Content
		html += """<span id="${var}"></span>"""

		//Units
		String un=settings["${var}_units"] ?: item.imperial
		String units=getAbbrev(un)
		Boolean disu=settings["${var}_display_units"]!=null ? settings["${var}_display_units"] : true
		if(disu && item.imperial != sNONE && units != "unknown") html+="""<span>${units}</span>"""

		//Right Icon
		if(item.icon != sNONE && item.icon_loc == "right"){
			html+="""<span>${item.icon_space}</span>"""
			html+="""<span class="mdi ${item.icon}"></span>"""
		}
		html += """</div>"""
	}
	html += """
	</div>
"""

	return html

}

/*
String defineHTML_globalVariables(){
	String html="""
		var sunrise;
		var sunset;
		let options=[];
		let pws_data=[];
		let currentTemperature;
"""
} */


String getGraph_forecast(){
//	String fullSizeStyle="margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden"

	String html
	html=defineHTML_Header_forecast()
	html += "<head><style>"
	//CSS
	html += defineHTML_CSS_forecast()
	html += """</style></head><body onload="initializeForecast()">"""
	html += defineHTML_Tile_forecast()

	html+="</body></html>"

	return html
}

//oauth endpoints

String getData_forecast(){
	def data= parent.getWData() // getPWSData()
	//String tdata=parent.getOpenWeatherData() // TODO parent.getWData()
	//if(isEric())myDetail null,"getData_forecast: $data",iN2
	return JsonOutput.toJson(data)
}








/*
 * TODO: Longtermstorage methods
 */

def mainLongtermstorage(){

	dynamicPage(name: "mainPage"){
		List container
		hubiForm_section(tDesc()+" Options", 1, "tune", sBLK){
			container=[]
			container << hubiForm_page_button("Select Device/Data", "deviceSelectionPage", "100%", "vibration")
			container << hubiForm_page_button("Configure/Report Data Storage", "graphSetupPage", "100%", "poll")
			hubiForm_container(container, 1)

		}

		put_settings(false)
	}
}

def deviceLongtermstorage(){

	if(password && username){
		log.debug("Username and Password set")
	}

	dynamicPage(name: "deviceSelectionPage", nextPage:"attributeConfigurationPage"){

		List container
		hubiForm_section("Login Information", 1, sBLK, sBLK){
			if(settings["hpmSecurity"]==null){
				settings["hpmSecurity"]=true
				app.updateSetting("hpmSecurity", true)
			}

			container=[]
			container << hubiForm_switch ((sTIT): "<b>Use Hubitat Security?</b>",
					(sNM): "hpmSecurity", default: true, submit_on_change: true)

			hubiForm_container(container, 1)

			if((Boolean)settings["hpmSecurity"]){
				input "username", "string",(sTIT): "Hub Security username", required: false, submitOnChange: true
				input "password", "password",(sTIT): "Hub Security password", required: false, submitOnChange: true
			}
		}
		if((Boolean)settings["hpmSecurity"] && !login()){
			hubiForm_section("Login Error", 1, sBLK, sBLK){
				container=[]
				container << hubiForm_text("""<b>CANNOT LOGIN</b><br>If you have Hub Security Enabled, please put in correct login credentials<br>
																If not, please deselect <b>Use Hubitat Security</b>""")
				hubiForm_container(container, 1)
			}

		}else{
			hubiForm_section("Sensor and Attribute Selection", 1, sBLK, sBLK){

				input "sensors", "capability.*",(sTIT): "<b>Sensor Selection for Long Term Storage</b>", multiple: true, submitOnChange: true

				if(sensors){

					List<Map> final_attrs
					final_attrs=[]
					for(sensor in (List)sensors){
						try{
							final_attrs=[]
							//List attributes_=sensor.getSupportedAttributes()
							List<String> attributes_=sensor.getSupportedAttributes().collect{ it.getName() }.unique().sort()
							attributes_.each{ String attribute_->
								//String name=attribute_.getName()
								def v= sensor.currentState(attribute_,true)
								if(v != null){
									final_attrs << [(attribute_) : "${attribute_} ::: [${v.getValue()}]"]
								}
							}
							final_attrs=final_attrs.unique(false)
						}catch(e){
							final_attrs=[["1" : "ERROR"]]
							error "Error: ",null,iN2,e
						}
						String sensor_name=gtLbl(sensor)
						input( (sTYPE): sENUM, (sNM): "${gtSensorId(sensor)}_attributes",(sTIT): "${sensor_name} attribute(s) to Store",
								required: true, multiple: true, options: final_attrs, submitOnChange: false)
					}
				}
			}
		}
	}
}

def optionsLongtermstorage(){

	def hoursEnum=1..24

//	def df=new DecimalFormat("#0.0")

	dynamicPage(name: "attributeConfigurationPage"){
		for(sensor in (List)sensors){
			String sid=gtSensorId(sensor)
			List<String> att=(List<String>)settings["${sid}_attributes"]
			if(att){
				att.each{ String attribute->
					String attr=attribute.replaceAll(sSPC, "_")

					String sensor_name=gtLbl(sensor)
					hubiForm_section("${sensor_name} (${attribute})", 1, sBLK, sBLK){

						storageLimitInput(sid, attr)

						String s="${sid}_${attr}".toString()
						input( (sTYPE): sENUM, (sNM): s+"_time_every",(sTIT): "Attempt to Store Data Every X Hours",
								required: true, multiple: false, options: hoursEnum, submitOnChange: false, defaultValue: 1)

						input( (sTYPE): "time", (sNM): s+"_time",(sTIT): "Time to Start Storing Data",
								required: false, multiple: false, submitOnChange: false, defaultValue: "00:00")

						//quantInput(sid,attr)

						List container
						container=[]

						List<Map> events=getAllDataLimit(sensor, attribute, 60)
						Integer num_events=events?.size()
						Date now=new Date()
						if(num_events > 2){

							// TODO
							def span=(((Date)events[num_events-1][sDT]).getTime()-((Date)events[0][sDT]).getTime())/(1000*60*60*24)
							def since=(now.getTime() - ((Date)events[0][sDT]).getTime())/(1000*60*60)

							List quantData= doQuant(events, sid, attr, true)

							Long frequency=averageFrequency(events)
							container << hubiForm_sub_section("Estimated Storage Consumption")
							container << hubiForm_text("<b>Total Events:</b> ${quantData.size()} quantized (${num_events} raw data)")
							container << hubiForm_text("<b>First Event:</b> ${events[0][sDT]} (<b>${round(since)}</b> hours ago)")
							container << hubiForm_text("<b>Frequency of raw data:</b> 1 event every ${round(frequency/(1000*60))} minutes")

							List subcontainer
							subcontainer=[]
							subcontainer << hubiForm_text(sBLK)
							subcontainer << hubiForm_text("<b>Daily Storage</b>")
							subcontainer << hubiForm_text("<b>Weekly Storage</b>")
							subcontainer << hubiForm_text("<b>Monthly Storage</b>")
							subcontainer << hubiForm_text("<b>Yearly Storage</b>")
							container << hubiForm_subcontainer([objects: subcontainer, breakdown: [0.2, 0.2, 0.2, 0.2, 0.2]])

							Integer averageSize= 34 // 50
							//Map storage=getCurrentDailyStorage(sensor, attribute)
							//subcontainer << hubiForm_text(storage.num_events.toString())
							//subcontainer << hubiForm_text(convertStorageSize((Integer)storage.size))
							subcontainer=[]
							Integer daily
							daily=((num_events/span)*averageSize).toInteger()
							subcontainer << hubiForm_text("Raw Data")
							subcontainer << hubiForm_text(convertStorageSize(daily))
							subcontainer << hubiForm_text(convertStorageSize(daily*7))
							subcontainer << hubiForm_text(convertStorageSize(daily*30))
							subcontainer << hubiForm_text(convertStorageSize(daily*365))
							container << hubiForm_subcontainer([objects: subcontainer, breakdown: [0.2, 0.2, 0.2, 0.2, 0.2]])

							subcontainer=[]
							daily=((quantData.size()/span)*averageSize).toInteger()
							subcontainer << hubiForm_text("Quantized Data")
							subcontainer << hubiForm_text(convertStorageSize(daily))
							subcontainer << hubiForm_text(convertStorageSize(daily*7))
							subcontainer << hubiForm_text(convertStorageSize(daily*30))
							subcontainer << hubiForm_text(convertStorageSize(daily*365))
							container << hubiForm_subcontainer([objects: subcontainer, breakdown: [0.2, 0.2, 0.2, 0.2, 0.2]])

						}

						hubiForm_container(container, 1)
					}
				}
			}
		}
	}
}

def graphLongtermstorage(){
	dynamicPage(name: "graphSetupPage"){
		if(sensors){
			List container
			List subcontainer
			hubiForm_section("Current Attribute Storage", 1, sBLK, sBLK){
				container=[]
				subcontainer=[]

				subcontainer << hubiForm_text("<b>Sensor</b>")
				subcontainer << hubiForm_text("<b>Attribute</b>")
				subcontainer << hubiForm_text("<b>Number of Events</b>")
				subcontainer << hubiForm_text("<b>First Event Time</b>")
				subcontainer << hubiForm_text("<b>Last Event Time</b>")
				subcontainer << hubiForm_text("<b>File Size</b>")

				container << hubiForm_subcontainer([objects: subcontainer, breakdown: [0.2, 0.2, 0.2, 0.2, 0.2, 0.2]])

				Double totalS
				totalS=0.0D
				for(sensor in (List)sensors){
					String sid=gtSensorId(sensor)
					List<String> att=(List<String>)settings["${sid}_attributes"]
					if(att){

						att.each{ String attribute->

							String sensor_name=gtLbl(sensor)

							subcontainer=[]

							//appendFile_LTS(sensor, attribute)

							Map storage=getCurrentDailyStorage(sensor, attribute)

							String filename_=getFileName(sensor, attribute)

							String uri_="http://${location.hub.localIP}:8080/local/${filename_}"

							subcontainer << hubiForm_text(sensor_name, uri_)
							subcontainer << hubiForm_text(attribute, uri_)
							subcontainer << hubiForm_text(storage.num_events.toString())
							subcontainer << hubiForm_text(formatTime((Date)storage.first))
							subcontainer << hubiForm_text(formatTime((Date)storage.last))
							subcontainer << hubiForm_text(convertStorageSize((Integer)storage.size))
							totalS += (Integer)storage.size

							container << hubiForm_subcontainer([objects: subcontainer, breakdown: [0.2, 0.2, 0.2, 0.2, 0.2, 0.2]])

						}
					}
				}
				container << hubiForm_text("<b>Total Storage:</b> ${convertStorageSize(totalS.toInteger())}")
				hubiForm_container(container, 1)
			}
		}
	}
}




// TODO not used?
/** LTS only called by parent is LTS stream with quant enabled?*/
Boolean isQuant(id, String attribute){
	if(isStorage(id,attribute)){
		def sensor=sensors?.find{it.id == id}
		String s= "${gtSensorId(sensor)}_${attribute}_quantization"
		String ts1= s+"_function"
		return !(settings[ts1]==sNONE || settings[s]==null || settings[s]==s0)
	}
	return false
}

/** LTS only called by parent is LTS stream enabled? */
Boolean isStorage(id, String attribute){
	def sensor=sensors?.find{it.id == id}
	if(sensor != null){
		List<String> att=(List<String>)settings["${id}_attributes"]
		return att.find{ it == attribute } != null
	}
	return false
}

/** LTS only, called by schedule to add data to file from device events in DB */
void updateData_LTS(Map data){

	if(isEric())myDetail null,"updateData $data",i1
	Map theEvent
	theEvent=[:]+data

	Map qres=queueSemaphore(data)

//	log.warn "qres:$qres"
	String msgt
	msgt="queued"
	if(!(Boolean)qres.exitOut){
		String pNm=sAppId()

		while(true){
			def sensor=sensors?.find{ it.id == theEvent.id }
			if(sensor) appendFile_LTS(sensor, sMs(theEvent,sATTR))
			else warn "Sensor not found ${theEvent}",null
			theEvent=null

			getTheLock(pNm,'update Data')
			List<Map> evtQ
			evtQ=theQueuesVFLD[pNm]
			if(!evtQ){
				if(theSemaphoresVFLD[pNm]<=(Long)qres.semaphore){
					msgt='Released Lock and exiting'
					theSemaphoresVFLD[pNm]=lZ
					theSemaphoresVFLD=theSemaphoresVFLD
				}
				releaseTheLock(pNm)
				break
			}else{
				evtQ=theQueuesVFLD[pNm]
				List<Map>evtList=evtQ //.sort{ Map it -> lMt(it) }
				theEvent=evtList.remove(0)
				Integer qsize=evtList.size()
				theQueuesVFLD[pNm]=evtList
				theQueuesVFLD=theQueuesVFLD
				releaseTheLock(pNm)

				if(qsize>i20)warn "large queue size ${qsize}".toString(),null
			}
		}
	}
	if(isEric())myDetail null,"update Data ${msgt}"
}



// Scheduling functions

void setupCron(sensor, String attribute){

	if(isEric())myDetail null,"setupCron $sensor $attribute",i1
	String dateFormat="yyyy-MM-dd'T'HH:mm:ss.SSSZ"

	String sid=gtSensorId(sensor)
	String attr=attribute.replaceAll(sSPC, "_")
	Date date=wtimeToday( (String)settings["${sid}_${attr}_time"], mTZ())
	//Date date=Date.parse(dateFormat, (String)settings["${sid}_${attr}_time"])
//error "object: ${describeObject(settings["${sid}_${attr}_time_every"])}",null
//log.warn myObj(settings["${sid}_${attr}_time_every"])
	Integer repeat=((String)settings["${sid}_${attr}_time_every"]).toInteger()
//log.warn "$date $repeat ${sensor.id}"
	addToSched(hrs: date.getHours(), mins: date.getMinutes(), repeatHrs: repeat, sid: sid, (sATTR): attribute)
	//schedule("0 ${date.getMinutes()} ${date.getHours()}/${repeat} ? * * *", updateData, [overwrite: false, data: [id: sid, attribute: attribute]])
	if(isEric())myDetail null,"setupCron $sensor $attribute"
}

private void clearSch(){
	String pNm=sAppId()
	getTheLock(pNm,'clearSch')

	atomicState.sched=[]

	releaseTheLock(pNm)
}

private addToSched(Map data){
	if(isEric())myDetail null,"addToSched",i1

	Integer hrs=data.hrs
	Integer mins=data.mins
	Integer repeatHrs=data.repeatHrs
	String sid=data.sid
	String attribute=sMs(data,sATTR)

	Long nextRun=pushAhead(hrs,mins,repeatHrs)

	String pNm=sAppId()
	getTheLock(pNm,'addToSched')

	List<Map> sched
	sched=atomicState.sched
	sched=sched != null ? sched : []
	if(sched) unschedule()
	sched << [hrs: hrs, mins: mins, repeatHrs: repeatHrs, sid: sid, (sATTR): attribute, nextRun: nextRun]

	atomicState.sched=sched

	releaseTheLock(pNm)

	if(isEric())myDetail null,"addToSched"
}

@CompileStatic
Long pushAhead(Integer hrs, Integer mins, Integer repeatHrs){
	Long firstOffset= hrs*3600000 + mins*60000
	Long baset= getMidnightTime() + firstOffset
	Long endt=getNextMidnightTime()
	Long repeatT=repeatHrs*3600000
	Long res
	res=baset
	Long n=wnow()
	while (res<n && res<endt){
		res += repeatT
	}
	if(res > endt) res= endt + firstOffset
	return res
}

private Long getMidnightTime(){ return wtimeToday('00:00',mTZ()).getTime() }
private Long getNextMidnightTime(){ return wtimeTodayAfter('23:59','00:00',mTZ()).getTime() }
private Date wtimeToday(String str,TimeZone tz){ return (Date)timeToday(str,tz) }
private Date wtimeTodayAfter(String astr,String tstr,TimeZone tz){ return (Date)timeTodayAfter(astr,tstr,tz) }
private void wrunInMillis(Long t,String m,Map d){ runInMillis(t,m,d) }

private runNextSched(Map a=[:]){
	String msg
	msg="runNextSched"
	if(isEric())myDetail null,msg,i1

	String pNm=sAppId()
	Boolean didSomething, didSched
	didSomething=false
	didSched=false

	getTheLock(pNm,msg)

	List<Map> sched
	sched=atomicState.sched
	sched=(sched!=null) ? []+sched : []
	Long nextSched
	nextSched=0L

	Integer i
	for(i=0; i< sched.size(); i++){
		Map s=sched[i]
		Long nextRun
		nextRun=s.nextRun
		Integer hrs=s.hrs
		Integer mins=s.mins
		Integer repeatHrs=s.repeatHrs
		String sid=s.sid
		String attribute=s[sATTR]
		if(nextRun < wnow()){
			didSomething=true
			nextRun=pushAhead(hrs,mins,repeatHrs)
			s.nextRun=nextRun
			if(didSomething) atomicState.sched=sched

			releaseTheLock(pNm)

			updateData_LTS([(sID): sid, (sATTR): attribute])

			getTheLock(pNm,msg+' L')
		}
		if(!nextSched) nextSched= nextRun
		if(nextRun< nextSched) nextSched=nextRun
	}

	Long n=wnow()
	if(nextSched>n){
		Long t=nextSched-wnow()
		didSched=true
		wrunInMillis(t,"runNextSched", [:])
		state.nextSched=nextSched

		if(isEric())myDetail null,msg+" schedule in $t msecs",iN2
	}else{
		if(isEric())myDetail null,msg+" no nextsched $nextSched or bad choice $n",iN2

	}

	releaseTheLock(pNm)

	if(!didSomething && !didSched) msg += " did nothing"
	if(isEric())myDetail null,msg
}

void checkSched(){
	Long next
	next=state.nextSched
	next= next ?: 0L
	if(wnow() > next+900000L){ // 15 mins late
		String msg='checkSched'
		if(isEric())myDetail null,msg,i1
		runNextSched()
		if(isEric())myDetail null,msg
	}
}





// TODO quant functions

/** returns internal format entry */
@CompileStatic
Map sum(List<Map> events, Integer decimals, Boolean round, Integer granularity){
	Float sum
	sum=new Float(0)
	for(Map event in events){
		sum += Float.valueOf(event[sVAL].toString())
	}

	Map tdate=[(sDT): events[events.size()-1][sDT], boundary: round, granularity: granularity]
	Date d=roundDate(tdate)
	return [(sDT): d, (sVAL): sum.round(decimals), (sT): d.getTime()]
}

/** returns internal format entry */
@CompileStatic
Map average(List<Map>events, Integer decimals, Boolean round, Integer granularity){
	Float sum
	sum=new Float(0)
	Integer sz=events.size()
	for(Map event in events){
		sum += Float.valueOf(event[sVAL].toString())
	}
	sum /= sz

	Map tdate=[(sDT): events[sz-1][sDT], boundary: round, granularity: granularity]
	Date d=roundDate(tdate)
	return [(sDT): d, (sVAL): sum.round(decimals), (sT): d.getTime(), q:1]
}

/** returns internal format entry */
@CompileStatic
Map min(List<Map>events, Integer decimals, Boolean round, Integer granularity){
	Float min
	min=Float.valueOf(events[0][sVAL].toString())
	for(Map event in events){
		Float v=Float.valueOf(event[sVAL].toString())
		min=v < min ? v : min
	}

	Map tdate=[(sDT): events[events.size()-1][sDT], boundary: round, granularity: granularity]
	Date d=roundDate(tdate)
	return [(sDT): d, (sVAL): min.round(decimals), (sT): d.getTime()]
}

/** returns internal format entry */
@CompileStatic
Map max(List<Map>events, Integer decimals, Boolean round, Integer granularity){
	Float max
	max=Float.valueOf(events[0][sVAL].toString())
	for(Map event in events){
		Float v=Float.valueOf(event[sVAL].toString())
		max=v > max ? v : max
	}

	Map tdate=[(sDT): events[events.size()-1][sDT], boundary: round, granularity: granularity]
	Date d=roundDate(tdate)
	return [(sDT): d, (sVAL): max.round(decimals), (sT): d.getTime()]
}

/** returns internal format entry */
@CompileStatic
Map count(List<Map>events, Integer decimals, Boolean round, Integer granularity){

	Integer sz=events.size()
	Map tdate=[(sDT): events[sz-1][sDT], boundary: round, granularity: granularity]
	Date d=roundDate(tdate)
	return [(sDT): d, (sVAL): sz, (sT): d.getTime(), q:1]
}

/*
static Long getTime(String text){

	String dateFormat="yyyy-MM-dd'T'HH:mm:ss.SSSZ"
	//String dateFormat="yyyy-MM-dd'T'HH:mm:ssX"
	return Date.parse(dateFormat, text).getTime()

} */

/** round a date based on quant settings */
Date roundDate(Map map){

	Date date=(Date)map[sDT]
	Boolean boundary=map.boundary != null ? (Boolean)map.boundary : false
	Integer granularity=map.granularity as Integer

	if(!boundary) return date

	Date nearest
	nearest=date
	if(granularity > 60 && granularity < 1440)
		nearest=org.apache.commons.lang3.time.DateUtils.truncate(date, Calendar.HOUR_OF_DAY)
	else if(granularity == 1440)
		nearest=org.apache.commons.lang3.time.DateUtils.truncate(date, Calendar.DAY_OF_MONTH)

	return nearest
}

/**
 *
 * @param events - internal format
 * @param mins
 * @param funct
 * @param dec
 * @param boundary
 * @param toStore
 * @return - internal format
 */
List quantizeData(List<Map> events, String mins, String funct, Integer dec, Boolean boundary, Boolean toStore){

	Integer minutes=mins as Integer
	Integer sz
	String s
	s='quantizeData '

	sz=events.size()
	if(isEric())myDetail null,s+"mins: $mins func: $funct decimals: $dec boundary: $boundary size: $sz",i1

	if(minutes==0 || funct==sNONE){
		if(isEric())myDetail null,s+"no change"
		return events
	}

	Integer decimals=dec as Integer

	Long milliSeconds=minutes*1000*60
	List<Map> newEvents
	newEvents=[]
	try{

		Long stop
		stop=roundDate([(sDT): events[0][sDT], granularity: minutes, boundary: boundary]).getTime() + milliSeconds

		List<Map> tempEvents
		tempEvents=[]
		Integer idx
		idx=0

		Map newEntry
		Long currTime

		while (idx < events.size()){
			currTime=roundDate([(sDT): events[idx][sDT], granularity: minutes, boundary: boundary]).getTime()

			if(currTime > stop){
				sz=tempEvents.size()
				newEntry=tempEvents[0]
				if(sz == 1 && newEntry.q == 1){ // deals with count cannot be re-processed
					if(isEric()) trace "DID NOT REPROCESS "+s+"$funct $sz",null
					newEvents.add(newEntry)
				}else if(sz > 0 ){
					if(isEric()) trace s+"$funct $sz",null
					newEntry="${funct}"(tempEvents, decimals, boundary, minutes)
					newEvents.add(newEntry)
				}
				stop += milliSeconds
				tempEvents=[]
			}
			tempEvents.add(events[idx])
			idx++
		}

		// TODO remove this
		// The last events are not quant'd
		//  (sum, average, min, max, count)
		sz=tempEvents.size()
		if(	(sz == 1 && tempEvents[0].q==1) ||
			(sz>0 && toStore && funct in ['average','count']) ){ // don't screw up average, count -> leave last unprocessed

			if(isEric()) trace s+"$funct adding $sz $tempEvents ",null
			newEvents=newEvents + tempEvents
		}else if(sz != 0){
			if(isEric()) trace s+"LAST $funct $sz",null
			newEntry="${funct}"(tempEvents, decimals, boundary, minutes)
			newEvents.add(newEntry)
		}

	}catch(e){
		error s,null,iN2,e
	}
	sz=newEvents.size()
	if(isEric())myDetail null,s+"Final size $sz"
	return newEvents
}


// shared
def storageLimitInput(String sid, String attribute, String defl="7", String varn=sNL){

	List<Map<String,String>> storageEnum=[
			["1" : "1 Day"], ["2" : "2 Days"], ["3" : "3 Days"], ["4" : "4 Days"], ["5" : "5 Days"], ["6" : "6 Days"],
			["7" : "1 Week"], ["14" : "2 Weeks"], ["21" : "3 Weeks"],
			["30" : "1 Month"], ["60" : "2 Months"], ["91" : "3 Months"], ["121" : "4 Months"], ["152" : "5 Months"], ["182" : "6 Months"],
			["213" : "7 Months"], ["243" : "8 Months"], ["274" : "9 Months"], ["304" : "10 Months"], ["334" : "11 Months"],
			["365" : "1 Year"], ["730" : "2 Years"], ["1095" : "3 Years"], ["1461" : "4 Years"]]

	String s= varn ?: (sid+'_'+attribute+'_storage')
	input( (sTYPE): sENUM, (sNM): s,(sTIT): "Duration of Storage to Maintain",
			required: false, multiple: false, options: storageEnum, submitOnChange: false, defaultValue: defl)
}







static Long averageFrequency(List<Map> events){
	Long sum
	sum=0
	Integer i
	for(i=1; i<events.size(); i++){
		// TODO
		sum += ((Date)events[i][sDT]).getTime() - ((Date)events[i-1][sDT]).getTime()
	}
	return Math.round(sum/events.size())
}


/** pull device events from HE DB */
List<Map> getEvents(Map map){

	if(isEric())myDetail null,"getEvents $map",i1
	try{
		def sensor=map.sensor
		String attribute=map[sATTR]
		Integer days=(Integer)map.days

		Date then
		if(map.start_time){
			then=(Date)map.start_time
		}else{
			Date now=new Date()
			then=now
			use (TimeCategory){
				then -= days.days
			}
		}

		//TODO remove date
		List<Map> respEvents
		respEvents=(List<Map>)sensor.statesSince(attribute, then, [max: 2000]).collect{ [ (sDT): it.date, (sVAL): it.value, (sT): ((Date)it.date).getTime()] }
		respEvents=respEvents.flatten() as List<Map>
		respEvents=respEvents.reverse() as List<Map>

		if(isEric())myDetail null,"getEvents $map ${respEvents.size()}"
		return respEvents as List<Map>
	}catch(e){
		error "getEvents",null,iN2,e
	}
	if(isEric())myDetail null,"getEvents"
	return null
}


Boolean login(){
	if((Boolean)settings["hpmSecurity"]){
		Boolean result
		result=false
		try{
			httpPost(
					[
							uri: "http://127.0.0.1:8080",
							path: "/login",
							query: [ loginRedirect: "/" ],
							body: [
									username: username,
									password: password,
									submit: "Login"
							],
							textParser: true,
							ignoreSSLIssues: true
					]
			){ resp ->
				if(resp.data?.text?.contains("The login information you supplied was incorrect."))
					result=false
				else{
					state.cookie=((List) ((String)resp?.headers?.'Set-Cookie')?.split(';'))?.getAt(0)
					result=true
				}
			}
		}catch(e){
			error "Error logging in: ",null,iN2,e
			result=false
		}
		return result
	}
	return true
}

Boolean fileExists(sensor, String attribute, String fname=sNL){

	String filename_=fname ?: getFileName(sensor, attribute)

	String uri="http://${location.hub.localIP}:8080/local/${filename_}"

	Map params=[
			uri: uri,
			textParser: true,
	]

	Boolean res
	res=false
	try{
		httpGet(params){ resp ->
			if(resp.status==200) res=true
		}
	}catch(e){
		String sensor_name=gtLbl(sensor)
		if(e.message.contains("Not Found")){
			debug "File DOES NOT Exist for ${sensor_name} (${attribute})",null,iN2
		}else{
			error"Find file ${sensor_name} (${attribute}) ($filename_} :: Exception: ",null,iN2,e
		}
	}
	return res
}

@Field volatile static Map<String,String> readTmpFLD=[:]

/**
 * returns Map that has internal format in map.data
 * @param sensor
 * @param attribute
 * @param fname
 * @return Map  [ size: x, data: List<Map> [[date: date, (sVAL): v, t: t], ....] ]
 */
Map readFile(sensor, String attribute, String fname=sNL){

	String s= "readFile $sensor $attribute $fname"
	if(isEric())myDetail null,s,i1

	String filename_=fname ?: getFileName(sensor, attribute)
	String pNm=filename_
	if(readTmpFLD[pNm]==sNL){ readTmpFLD[pNm]=sBLK; readTmpFLD= readTmpFLD }
	try{
		Integer sz=readTmpFLD[pNm].size()
		//myDetail null,"pNm: ${pNm} cache sz: $sz",iN2
		if(sz> 4){
			JsonSlurper jsonSlurper=new JsonSlurper()
			List<Map> parse=convertToList((List<Map>)jsonSlurper.parseText(readTmpFLD[pNm]))
			if(isEric())trace "readFile cache hit",null
			if(isEric())myDetail null,s
			return [size: sz, data: parse ]
		}
	} catch(ignored){}

	readTmpFLD[pNm]=sBLK
	readTmpFLD= readTmpFLD
	String uri="http://${location.hub.localIP}:8080/local/${filename_}"
	Map params=[
			uri: uri,
			contentType: "text/plain; charset=UTF-8",
			textParser: true,
			headers: [ "Cookie": state.cookie, "Accept": 'application/octet-stream' ]
	]

	try{
		httpGet(params){ resp ->
			if(resp.status==200 && resp.data){
				Integer i
				char c
				i=resp.data.read()
				while(i!=-1){
					c=(char)i
					readTmpFLD[pNm]+=c
					i=resp.data.read()
				}
				//log.warn "pNm: ${pNm} data: ${data} file: ${readDataFLD[pNm]}"
			}else{
				error "Read Response status $resp.status",null
			}
		}
		readTmpFLD= readTmpFLD
		Integer sz
		sz=readTmpFLD[pNm].size()
		//myDetail null,"after read pNm: ${pNm} cache sz: $sz",iN2
		if(sz){
			String sc
			sc = readTmpFLD[pNm]
			while(sz && sc[sz-1]!= ']'){
				sc = sc.substring(0,sz-1)
				sz=sc.size()
			}
			readTmpFLD[pNm]=sc
		}
		//myDetail null,"after TRIM pNm: ${pNm} cache sz: $sz",iN2
		List<Map> parse
		parse=[]
		if(sz>1){
			JsonSlurper jsonSlurper=new JsonSlurper()
			parse=convertToList((List<Map>)jsonSlurper.parseText(readTmpFLD[pNm]))
		}else sz=0
		if(isEric())myDetail null,s+" $sz"
		return [size: sz, data: parse ]
	}catch(e){
		String sensor_name=gtLbl(sensor)
		String ts1= " for ${sensor_name} (${attribute}) ($filename_}"
		if(e.message.contains("Not Found")){
			debug "File DOES NOT Exist"+ts1,null,iN2
		}else{
			error "Read File Data"+ts1+" :: Exception: ",null,iN2,e
		}
	}
	readTmpFLD[pNm]=sNL
	readTmpFLD= readTmpFLD
	if(isEric())myDetail null,s
	return [size: 0, data: [] ]
}

String getFileName(sensor, String attribute){
	String attr=attribute.replaceAll(sSPC, "_")
	return "webCoRE_LTS_${gtSensorId(sensor)}_${attr}.json"
}

/** receives internal format */
List<Map> pruneData(List<Map> input_data, Integer days){

	if(isEric())myDetail null,"pruneData ${input_data.size()} time: $days",i1

	if(days == 0 || !input_data){
		if(isEric())myDetail null,"pruneData nochange"
		return input_data
	}
	List return_data=[]+input_data
	if(input_data.size() > 0){

		Date then
		then=new Date()
		use (TimeCategory){
			then -= days.days
		}

		Long startDate=then.getTime()

		Long date
		date=(Long)input_data[0].t

		while (date && return_data && date < startDate){
			if(isEric())debug "date: $date startDate: $startDate return_data[0]: ${return_data[0]}",null
			Map a=return_data.remove(0)
			date=return_data ? (Long)return_data[0].t : null
		}
	}
	if(isEric())myDetail null,"pruneData ${return_data.size()} time: $days"
	return return_data
}

static List<Map> addData(List<Map> main, List<Map> append){

	List<Map> return_data=main
	append.each{Map data->
		return_data << data
	}
	return return_data
	// sort it just in case?
	//return_data=events ? return_data + events : return_data
	//return_data=return_data.flatten() as List<Map>
}

/** returns internal format */
List<Map> getFileData(sensor, String attribute, String fname=sNL){
	String s
	s= "getFileData $sensor $attribute $fname"
	if(isEric())myDetail null,s,i1

	List<Map> parse_data
	parse_data =[]

	Map json= readFile(sensor,attribute,fname)
	if(json?.data){
		parse_data = (List<Map>)json.data
		s += " ${parse_data.size()}"
	}

	if(isEric())myDetail null,s
	return parse_data
}




/** shared  - old LTS only method */
Map quantParams(sensorId, String attr){
	String sid=sensorId.toString()
	String s="${sid}_${attr}".toString()
	def v
	v= settings[s+"_quantization"]
	String quantization_minutes= v!=null ? (String)v : s0
	v= settings[s+"_quantization_function"]
	String quantization_function= v ? (String)v : "average"
	v= settings[s+"_quantization_decimals"]
	Integer quantization_decimals= v!=null ? ((String)v).toInteger() : i1
	v= settings[s+"_boundary"]
	Boolean quantization_boundary= v!=null ? ((String)v).toBoolean() : false

	if(quantization_minutes!=s0 && quantization_function!=sNONE)
		return [qm: quantization_minutes, qf: quantization_function, qd: quantization_decimals, qb: quantization_boundary]
	return null
}

//shared
/** LTS only method - internal data format */
List<Map> doQuant(List<Map>data, sensorId, String attr, Boolean toStore){

	Map params= quantParams(sensorId,attr)

	if(params)
		return quantizeData(data, params.qm , params.qf, params.qd, params.qb, toStore)
	else
		return data
}





/**
 * Shared Sensor data only - used by graphs and LTS returns all sensor data, trying to go back at least but no more than maxdays
 *
 * @param sensor
 * @param attribute
 * @param maxdays
 * @return Internal data format as List<Map>  [[date: (Date)date, (sVAL): v, t: (Long)t], ....] & updates LTS file if in use
 */
List<Map>getAllDataLimit(sensor,String attribute, Integer maxdays=7){

	List<Map> data=getAllData(sensor,attribute,maxdays,true,false)

	Date then
	then=new Date()
	use (TimeCategory){
		then -= maxdays.days
	}
	Long gt=then.getTime()
	List<Map> all_data
	all_data= data.findAll{ Map it -> (Long)it.t > gt}

	return all_data
}

/**
 * Shared- Sensor data only - used by graphs and LTS returns all sensor data,
 * trying to go back at least mindays (may be more or less than this)
 *
 * @param sensor
 * @param attribute
 * @param mindays
 * @param add
 * @param updateFile - create lts file if it does not exist
 * @return Internal data format as List<Map>  [[date: (Date)date, (sVAL): v, t: (Long)t], ....]
 */
List<Map>getAllData(sensor,String attribute, Integer mindays=1461, Boolean add=true, Boolean updateFile=false){

	String sid=gtSensorId(sensor)

	if(isEric())myDetail null,"getAllData $sensor $attribute $sid $mindays $add $updateFile",i1

	List<Map> parse_data
	parse_data=[]
	Integer sz
	sz=-1

	Integer st= mindays
	Date then
	then=new Date()
	use (TimeCategory){
		then -= st.days
	}

	//warn "then is $then",null
	Boolean lts
	lts=false
	if( (sMs(settings,sGRAPHT)==sLONGTS && isStorage(sid,attribute)) || (Boolean)parent.ltsAvailable(sid,attribute)){
//		if(fileExists(sensor,attribute)){
		parse_data=getFileData(sensor, attribute)
		//Get the most Current Data
		sz=parse_data.size()
		if(sz) then=(Date)parse_data[sz-1][sDT]
		lts=true
//		}
	}

	//warn "then NOW is $then",null
	List<Map> all_data
	all_data=parse_data

	if(add){
		List<Map> respEvents=getEvents(sensor: sensor, (sATTR): attribute, start_time: then)
		if(respEvents){
			all_data=addData(parse_data, convertToList(respEvents))
		}
	}

	if(lts && all_data && sz==0 && updateFile) writeFile(sensor,attribute,all_data) // create file if does not exist

	if(!all_data && add){
		def state_=sensor.currentState(attribute,true)
		all_data= convertToList([[v:state_,t:wnow()]])
	}

	if(isEric())myDetail null,"getAllData ${all_data.size()}"
	return all_data
}


/**
 * LTS only method, sensor data only, requires LTS enabled for sensor/attribute
 *
 * @param sensor
 * @param attribute
 * @param fname
 */
void appendFile_LTS(sensor, String attribute, String fname=sNL){
	if(isEric())myDetail null,"appendFile_LTS $sensor $attribute",i1

	String attr=attribute.replaceAll(sSPC, "_")

	String sid=gtSensorId(sensor)

	Integer storage=(String)settings["${sid}_${attr}_storage"] as Integer
	List<Map> write_data
	write_data=getAllData(sensor,attribute,storage,true,false)

	try{

		if(write_data.size()){
			write_data=pruneData(write_data, storage)

			//write_data=doQuant(write_data, sid, attr, true)

			writeFile(sensor, attribute, write_data)

		}else{
			String filename_=fname ?: getFileName(sensor, attribute)
			String sensor_name=gtLbl(sensor)
			warn "Append File ${sensor_name} (${attribute}) ($filename_} nothing to write",null
		}

	}catch(e){
		String filename_=fname ?: getFileName(sensor, attribute)
		String sensor_name=gtLbl(sensor)
		error "Append File ${sensor_name} (${attribute}) ($filename_} :: Exception: ",null,iN2,e
	}

	if(isEric())myDetail null,"appendFile_LTS"
}

/**
 * Shared Returns internal format from from various file formats
 * @param json
 * @return Internal data format as List<Map>  [[date: (Date)date, (sVAL): v, t: (Long)t], ....]
 */
@CompileStatic
static List<Map> convertToList(List<Map>json){

	List<Map> return_data=[]

	Long t
	def v
	for(Map data in json){
		t=null
		if(data.containsKey(sT)){
			t= (Long)data[sT]
		}else if(data[sDT]){
			String dateFormat="yyyy-MM-dd'T'HH:mm:ssX"
			Date date=Date.parse(dateFormat, (String)data[sDT])
			t= date.getTime()
		}else if(data[sI]){
			t= (Long)data[sI]
		}
		Date date=new Date(t)
		v= data.containsKey(sV) ? data[sV] : data[sVAL]
		v= data.containsKey(sD) ? data[sD] : v
		if(data.containsKey('q'))
			return_data << [(sDT): date, (sVAL): v, (sT): t, q: data.q]
		else
			return_data << [(sDT): date, (sVAL): v, (sT): t]

	}
	return return_data
}

/** shared (LTS & fuel) only method - convert different formats to file format */
@CompileStatic
static List<Map> rtnFileData(List<Map> events){
	List<Map> file_data=[]
	def v
	Long t
	for(Map data in events){
		v= data.containsKey(sV) ? data[sV] : data[sVAL]
		v= data.containsKey(sD) ? data[sD] : v
		t= data.containsKey(sI) ? (Long)data[sI] : 0L
		t= data.containsKey(sT) ? (Long)data[sT] : ((Date)data[sDT]).getTime()
		if(data.containsKey('q'))
			file_data << [(sV): v, (sT): t, q:data.q]
		else
			file_data << [(sV): v, (sT): t]
	}
	return file_data
}

/** shared (LTS & fuel) only method - save different formats to file format */
Boolean writeFile(sensor, String attribute, List<Map> events, String fname=sNL){

	String s= "writeFile $sensor $attribute $fname"
	if(isEric())myDetail null,s,i1

	if(login()){

		String filename_=fname ?: getFileName(sensor, attribute)
		String pNm=filename_

		List<Map> file_data
		file_data=rtnFileData(events)
		String contents=file_data ? JsonOutput.toJson(file_data) : sBLK
		file_data=null

		if(readTmpFLD[pNm]==sNL){ readTmpFLD[pNm]=sBLK; readTmpFLD= readTmpFLD }
		Integer sz= readTmpFLD[pNm].size()
/*		Integer sz1= contents.size()
		myDetail null,"pNm: ${pNm} cache sz: $sz  new data: ${sz1}",iN2
		if(sz){
			String sc=readTmpFLD[pNm]
			String st=sc[sz-1]
			if(st=='\n') myDetail null, 'FOUND NEWLINE',iN2
			myDetail null,"last char CACHE DATA is ${sc[sz-1]}",iN2
			myDetail null,"last char NEW DATA is ${contents[sz1-1]}",iN2
		} */
		if(sz> 4 && sz==contents.size() && contents==readTmpFLD[pNm]){
			if(isEric()) trace "writeFile no changes",null
			if(isEric())myDetail null,s+" TRUE"
			return true
		}

		Date d=new Date()
		String encodedString="thebearmay$d".bytes.encodeBase64().toString()
		try{
			Map params=[
					uri: "http://127.0.0.1:8080",
					path: "/hub/fileManager/upload",
					query: [ "folder": "/" ],
					headers: [
							"Cookie": state.cookie,
							"Content-Type": "multipart/form-data; boundary=$encodedString"
					],
					body: """--${encodedString}
Content-Disposition: form-data; name="uploadFile"; filename="${filename_}"
Content-Type: "text/plain; charset=UTF-8"

${contents}

--${encodedString}
Content-Disposition: form-data; name="folder"


--${encodedString}--""",
					timeout: 300,
					ignoreSSLIssues: true
			]
			Boolean res
			res=false
			httpPost(params){ resp ->
				if(resp.status!=200){
					error "Write Response status $resp.status",null
					readTmpFLD[pNm]=sNL
				}else{
					readTmpFLD[pNm]=contents
					res=true
				}
			}
			readTmpFLD= readTmpFLD
			if(res){
				if(isEric())myDetail null,s+" TRUE"
				return true
			}
		}catch(e){
			String sensor_name=gtLbl(sensor)
			error "Write File ${sensor_name} (${attribute}) ($filename_} :: Exception: ",null,iN2,e
		}
		readTmpFLD[pNm]=sNL
		readTmpFLD= readTmpFLD
	}
	if(isEric())myDetail null,s+" FALSE"
	return false
}




/** LTS only method */
Map getCurrentDailyStorage(sensor, String attribute, String fname=sNL){
	Map json=fileExists(sensor,attribute,fname) ? readFile(sensor, attribute,fname) : null
	if(json?.data){

		List<Map> data=(List<Map>)json.data
		Integer size=(Integer)json.size

		Integer dsz=data.size()
		Date first
		Date then
		if(dsz){
			first = new Date((Long) data[0][sT])
			then = new Date((Long) data[dsz-1][sT])
		}else{
			first=null
			then=null
		}

		return [num_events: dsz, first: first, last: then, size: size]

	}else{

		try{
			Integer storage
			storage=(String)settings["${gtSensorId(sensor)}_${attribute}_storage"] as Integer
			storage=storage ?: 30
			List<Map> respEvents=getEvents(sensor: sensor, (sATTR): attribute, days: storage)

			writeFile(sensor, attribute, respEvents)

			return [num_events: respEvents.size(), first: respEvents[0][sDT], last: respEvents[respEvents.size()-1][sDT], size: respEvents.size()*34]

		}catch (e){
			error "Error: ",null,iN2,e
		}

	}
	return null
}


/** fuel stream method */
Map getCurrentDailyStorageFS(){
	List<Map> a=getFuelStreamData(null)
	List<Map> file_data
	file_data=rtnFileData(a) // we are measure as stored size
	Integer sz=file_data.toString().size()
	Map json=[size: sz, data: a ]
	if(json.data){

		List<Map> data=(List<Map>)json.data
		Integer size=(Integer)json.size

		Date first=new Date((Long)data[0][sT])
		Date then=new Date((Long)data[data.size()-1][sT])

		return [num_events: data.size(), first: first, last: then, size: size]
	}
	return null
}

/*
Map getSensor(String str){
	List<String> split=str.tokenize('.')
	def sensor=sensors?.find{ it.id == split[0]}
	return [ sensor: sensor, attribute: split[1] ]
} */

static String convertStorageSize(Integer num){
	DecimalFormat df=new DecimalFormat("#0.0")

	if(num < 1024){
		return df.format(num)+" bytes"
	}else if(num < 1048576){
		return df.format(num/1024.0)+" KB"
	}else{
		return df.format(num/1048576.0)+" MB"
	}

}

static String round(num){
	DecimalFormat df=new DecimalFormat("#0.0")
	return df.format(num.toString().toDouble())
}











/*
 * TODO: Fuel Stream
 */

def mainFuelstream(){
	dynamicPage(name: "mainPage",(sTIT): "Settings", uninstall: true, install: true){
		if( !((Boolean)settings.useFiles && (Boolean)state.useFiles) ){
			section('Use HE files for data storage'){
				input( (sTYPE): sBOOL, (sNM): "useFiles",(sTIT): "Use HE files for fuelstream storage?",
						required: false, multiple: false, submitOnChange: true, defaultValue: false)
			}
		}

		if((Boolean)settings.useFiles || (Boolean)state.useFiles){
			section('Security'){
				if(settings["hpmSecurity"]==null){
					settings["hpmSecurity"]=true
					app.updateSetting("hpmSecurity", [(sTYPE): sBOOL, (sVAL): "true"])
				}
				input( (sTYPE): sBOOL, (sNM): "hpmSecurity",(sTIT): "Use Hubitat Security",
						required: false, multiple: false, submitOnChange: true, defaultValue: true)

				if((Boolean)settings["hpmSecurity"]){
					input "username", "string",(sTIT): "Hub Security username", required: false, submitOnChange: true
					input "password", "password",(sTIT): "Hub Security password", required: false, submitOnChange: true
				}
			}
			if((Boolean)settings["hpmSecurity"] && settings.password && !login()){
				section('Login Error'){
					paragraph("""<b>CANNOT LOGIN</b><br>If you have Hub Security Enabled, please put in correct login credentials<br> If not, please deselect <b>Use Hubitat Security</b>""" )
				}
			}
		}

		section('Storage Limits'){
			input "maxSize", "number",(sTIT): "Max size of this fuelStream data in KB", defaultValue: 95
// Maxsize or n days (ie both limits hold)
			//input "storage_days", "number",(sTIT): "Max # of days of data in this fuelStream", defaultValue: 1461
			storageLimitInput(sNL, sNL,"1461",'storage_days')
		}

		List<Map> a
		a=getFuelStreamDBData(false)
		state.useFiles= (Boolean)settings.useFiles && !(a)
		section('Storage'){

			if((Boolean)settings.useFiles){
				String attribute=fuelNattr()
				def sensor=app
				Boolean fexists
				fexists= fileExists(sensor,attribute,fuelName())

				if(a){
					paragraph("Found DB Storage in use, with use files selected")
					input( (sTYPE): sBOOL, (sNM): "convertToFile",(sTIT): "Convert to File storage",
							required: false, multiple: false, submitOnChange: true, defaultValue: false)

					if((Boolean)settings.convertToFile){
						if(!fexists){
							if(writeFile(sensor, attribute, a,fuelName())){
								state.remove('fuelStreamData')
								info "Converted to file",null
								fexists=true
								state.useFiles= (Boolean)settings.useFiles

							}else{
								error "conversion to file failed",null
							}

						}else{
							paragraph("Found file exists with DB storage in use")
						}
						app.updateSetting("convertToFile", [(sTYPE): sBOOL, (sVAL): "false"])
					}
				}
			}

			Map storage=getCurrentDailyStorageFS()
			if(!(Boolean)settings.useFiles || !(Boolean)state.useFiles){
				paragraph("Using HE DB as storage")
			}
			if((Boolean)settings.useFiles && (Boolean)state.useFiles){
				paragraph("Using HE Files as storage")
			}
			Integer max=iMs(settings,'maxSize') ?: 95
			paragraph("Storage Limit: ${max}KB")
			paragraph("Current storage usage is ${convertStorageSize(storage.size)}")
			Integer storageSize=state.toString().size()
			paragraph("Current state usage is ${convertStorageSize(storageSize)}")
			paragraph("Details: ${storage}")
		}
	}
}


/**
 * methods called by webCoRE parent to operate on streams
 */
public void createStream(settings){
	fuelFLD=null
	// fuelstream does not have graphType set
	state.fuelStream=[(sI): settings.id, (sC): (settings.canister ?: sBLK), (sN): settings.name, w: 1, (sT): getFormattedDate(new Date())]
}

/**
 * Called to get list of streams in this app instance
 *  Can be filtered to fuelstreams only, or fuel and LTS.  Graphs have no stream
 *  Typical fuelstreams have 1 data set, LTS may have many data sets (each returned as a stream)
 * @return
 */
public List getFuelStreams(Boolean includeLTS){
	List<Map> res
	res = []
	if(includeLTS && sMs(settings,sGRAPHT)==sLONGTS){
		if(sensors){
			for(sensor in (List)sensors){
				String sid=gtSensorId(sensor)
				List<String> att=(List<String>)settings["${sid}_attributes"]
				if(att){
					for(String attribute in att){
						//make up stream descriptions
						String ltsdesc= sid+'_'+attribute
						res << [(sI):ltsdesc, (sC): 'LTS', (sN):ltsdesc,w:1,(sT): getFormattedDate(new Date())]
					}
				}
			}
		}

	}else{
		Map fs=(Map)state.fuelStream
		if(fs) res << fs
	}
	if(isEric())myDetail null,"getFuelStreams $includeLTS $res",iN2
	res
}

/** fuel stream or LTS only - called by main webCoRE for webCoRE console to get data in stream -> returns webCoRE IDE format */
public List<Map> listFuelStreamData(String streamid){
	if(isEric())myDetail null,"listFuelStreamData $streamid",iN2

	// [[ d: itemvalue, i: item.t]]
	List<Map> ideData=[]
	List<Map> res

	// if we are LTS, need to find proper stream based on id
	if(sMs(settings,sGRAPHT)==sLONGTS){
		String[] tname = streamid.split('_')
		String id =tname[0]
		String attribute= tname[1]
		res=null
		if(sensors && id && attribute){
			for(sensor in (List)sensors){
				if(id == gtSensorId(sensor)){
					res= getAllData(sensor,attribute,1461,true,false)
					break
				}
			}
		}

	}else{

		res=getFuelStreamData(null)
//		//getFuelStreamData().collect{ it + [(sT): getFormattedDate(new Date((Long)it.i))]}

	}

	if(res){
		for(Map data in res){
			def v=data.containsKey(sV) ? data[sV] : data[sVAL]
			Long t=data.containsKey(sT) ? (Long)data[sT] : ((Date)data[sDT]).getTime()
			ideData << [ (sD): v, (sI): t, (sT): getFormattedDate(new Date(t))]
		}
	}
	return ideData
}

/** fuel stream only - called by pistons to read entire stream, returns internal format */
public List<Map> readFuelStream(Map req){
	if(!req)return null
	if(isEric())myDetail null,"readFuelStream $req",iN2
	return getFuelStreamData(req)
}

/** fuel stream only - called by pistons to overwrite entire stream, input is internal format */
public void writeFuelStream(Map req){ // overwrite
	if(!req)return
	if(req.d instanceof List){
		if(isEric())myDetail null,"writeFuelStream $req",iN2
		storeFuelUpdate((List)req.d,req,true)
	}
}

/** fuel stream only - called by pistons to clear fuel stream */
public void clearFuelStream(Map req){
	if(!req)return
	if(isEric())myDetail null,"clearFuelStream $req",iN2
	storeFuelUpdate([],req,true)
}

/** fuel stream only - called by pistons to append data to fuel stream, adds current time to data added */
public void updateFuelStream(Map req){ // append
//	def canister=req.c ?: sBLK
//	def name=req.n
//	def instance=req.i
//	def data=req.d
//	def source=req.s

	if(isEric())myDetail null,"updateFuelStream $req",iN2
	if(!req)return
	List<Map> stream= getFuelStreamData(req)
	// [[ date: Date, (sVAL): v, t: long]]
	// old internal format conversion //Boolean a=stream.add([d: req.d, i: wnow()])
	Date n= new Date()
	Boolean a=stream.add([(sVAL): req[sD], (sDT): n, (sT): n.getTime()])
	storeFuelUpdate(stream,req)
}




// Internal methods

/** fuel stream only - return file name for this fuel stream */
String fuelName(){
	String s= getFSFileName('f'+app.id.toString(),fuelNattr())
	if(isEric())myDetail null,"fuelName $s",iN2
	return s
}

/** return cleaned name */
String getFSFileName(String sensorId, String attribute){
	String attr=attribute.replaceAll(sSPC, "_")
	String s= "WebCoRE_Fuel_${sensorId}_${attr}.json"
	if(isEric())myDetail null,"getFSFileName $s",iN2
	return s
}

/** fuel stream only - return an attribute string for this fuel stream */
@CompileStatic
String fuelNattr(){
	Map fs=(Map)gtSt("fuelStream")
//state.fuelStream=[i: settings.id, c: (settings.canister ?: sBLK), n: settings.name, w: 1, (sT): getFormattedDate(new Date())]
	String c=sMs(fs,sC) ?: sBLK
	String n=sMs(fs,sN)
	Integer i=iMs(fs,sI)
	String d='_'
	String attribute=c+d+n+d+i.toString()
	if(isEric())myDetail null,"fuelNattr $attribute",iN2
	return attribute.replaceAll(sSPC, d)
}

/** fuel stream only - returns internal format read from fuel stream based on storage settings */
public List<Map> getFuelStreamData(Map req,Boolean init=true){
	if(isEric())myDetail null,"getFuelStreamData $req $init",iN2
	// [[ date: Date, value, v, (sT): long]]
	if(!(Boolean)state.useFiles){
		return getFuelStreamDBData(init)
	}else return getFuelStreamFData()
}

/**
 * fuel stream only - returns internal format
 * @param init
 * @return Internal data format as List<Map>  [[date: date, (sVAL): v, t: t], ....]
 */
List<Map> getFuelStreamDBData(Boolean init=true){
	if(isEric())myDetail null,"getFuelStreamDBData $init",iN2
	// [[ date: Date, value, v, t: long]]
	if(!state.fuelStreamData){
		if(init) state.fuelStreamData=[]
	}

	return convertToList((List)state.fuelStreamData)
}

/** fuel stream only - returns internal format */
List<Map> getFuelStreamFData(){
	// [[ date: Date, (sVAL): v, t: long]]
	if(isEric())myDetail null,"getFuelStreamFData",iN2
	if((Boolean)state.useFiles){
		String attribute=fuelNattr()
		def sensor=app
		List<Map> stream= getFileData(sensor, attribute, fuelName())
		List<Map> tstor=(List)state[attribute] ?: []
		return stream+tstor
	}else{
		log.warn "file requested for fuelstream and file not enabled"
	}
	return null
}

/** fuel stream only - receives internal format, returns trimmed internal format */
@CompileStatic
List<Map> cleanFuelStream(List<Map> istream){
	//ensure max size is obeyed

	List<Map> stream
	//[date: date, (sVAL): v, t: t]
	stream=istream
	if(!stream) return []


	String msg
	msg=sBLK
	Integer osz=stream.size()

	//String s="${sid}_${attribute}".toString()   s+'_storage'
	Integer storage=(gtSetting("storage_days") ?: 1461) as Integer

	if(isEric())debug "cleanFuelStream, size: $osz, days: $storage",null

	List<Map> parse_data=pruneData(stream, storage)
	stream=parse_data

	Integer nsz
	nsz=stream.size()
	if(isEric())debug "cleanFuelStream first trim, size: $nsz, days: $storage",null

	List<Map> tstream
	tstream= rtnFileData(stream) // need to work with as stored size
	Double storageSize= tstream.toString().size() / 1024.0D
	Integer max=(gtSetting('maxSize') ?: 95) as Integer

	if(isEric())debug "cleanFuelStream prep, storageSize: $storageSize, max: $max",null
	Boolean a
	if(storageSize.toInteger() > max){
		Integer points=stream.size()
		Double averageSize=points > 0 ? (storageSize/points).toDouble() : 0.0D

		Integer pointsToRemove
		pointsToRemove=averageSize > 0 ? ((storageSize - max) / averageSize).toInteger() : 0
		pointsToRemove=pointsToRemove > 0 ? pointsToRemove : 0

		msg +="Size ${storageSize}KB Points ${points} Avg $averageSize Remove $pointsToRemove".toString()
		List<Map> toBeRemoved=stream.sort{ Map it -> it.t }.take(pointsToRemove)
		a=stream.removeAll(toBeRemoved)
	}

	nsz=stream.size()
	if(osz!=nsz){
		if(isEric())debug "Trimmed fuel stream, $osz, $nsz",null
		if(msg && isDbg()) debug msg,null
	}
	return stream
}



/** fuel stream only - receives internal format, stores as file format based on fuel stream storage settings */
void storeFuelUpdate(List<Map>istream,Map req,Boolean frc=false){
	if(isEric())myDetail null,"storeFuelUpdate $istream $req $frc",iN2
	Boolean res
	List<Map>stream
	stream=cleanFuelStream(istream)
	//[date: date, (sVAL): v, t: t]

	stream= rtnFileData(stream)
	if(!(Boolean)state.useFiles){
		res=storeFuelDBData(stream)
	}else res=storeFuelFileData(stream,frc)
	if(!res) warn "storeFuelUpdate failed",null
}

/** fuel stream only - receives file format, stores in HE DB */
Boolean storeFuelDBData(List<Map>stream){
	if(isEric())myDetail null,"storeFuelDBData $stream",iN2
	if(!(Boolean)state.useFiles){
		state.fuelStreamData=stream
		return true
	}
	return false
}

/** fuel stream only - receives file format, stores in file */
Boolean storeFuelFileData(List<Map>istream,Boolean frc){
	if(isEric())myDetail null,"storeFuelFileData $istream $frc",iN2
	if((Boolean)gtSt('useFiles')){
		String attribute=fuelNattr()
		def sensor=app
		List<Map>stream=istream

		/*
		Integer osz=istream.size()
		List<Map>stream=cleanFuelStream(istream)
		Integer nsz=stream.size()

		if(!frc && nsz>0 && osz==nsz){
			Long lst=nsz>1 ? (Long)stream[nsz-1].i : 0L
			Long lst2=nsz> 1 ? (Long)stream[nsz-2].i : 0L
			if((lst-lst2) < 1800000L){ // 30 mins
				List<Map> tstor=(List)state[attribute] ?: []
				if(tstor.toString().size()<2000 && tstor.size()<20){
					Map item=stream.pop()
					Boolean a= tstor.add(item)
					state[attribute]=tstor
					return true
				}
			}
		} */
		state[attribute]= []
		return writeFile(sensor, attribute, stream, fuelName())

	}
	return false
}

@CompileStatic
static String getFormattedDate(Date date=new Date()){
	SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
	format.setTimeZone(TimeZone.getTimeZone("UTC"))
	format.format(date)
}







// TODO: Keep updated0

@CompileStatic
static String cleanHtml(String htm){
	return htm.replace('\t', sBLK).replace('\n', sBLK).replace('  ', sBLK).replaceAll('> ','>').replaceAll(' >','>')
	//return htm.replace('\t', sSPC).replace('\n', sSPC).replace('    ', sSPC).replace('   ', sSPC).replace('  ',sSPC).replaceAll('> ','>').replaceAll(' >','>')
}

// Material-Design-lite
// https://getmdl.io

def hubiForm_container(List<String> containers, Integer inumPerRow=1, Boolean save=false){
	Integer numPerRow
	numPerRow=inumPerRow

	String style
	if(numPerRow == 0){
		style="""style="margin: 0 !important; padding: 0 !important;"""
		numPerRow=1
	}else{
		style=""
	}

	String html_
	html_="""
	<div class="mdl-grid" style="margin: 0 !important; padding: 0 !important;">
"""
	containers.each{String container->
		html_ += """<div class="mdl-cell mdl-cell--${12/numPerRow}-col-desktop mdl-cell--${8/numPerRow}-col-tablet mdl-cell--${4/numPerRow}-col-phone" ${style}>"""
		html_ += container
		html_ += """</div>
"""
	}
	html_ += """</div>
"""

	if(save) state.saveC=cleanHtml(html_)

	paragraph cleanHtml(html_)
}

static String hubiForm_subcontainer(Map map){

	List<String> containers=(List<String>)map.objects
	List<Number> breakdown=(List<Number>)map.breakdown
	String html_
	html_ =
			"""

	<div class="mdl-grid" style="margin: 0; padding: 0; ">
	"""
	Integer count
	count=0
	containers.each{String container->
		def sz_12=12*breakdown[count]
		def sz_8=8*breakdown[count]
		def sz_4=4*breakdown[count]
		html_ += """		<div class="mdl-cell mdl-cell--${sz_12.intValue()}-col-desktop mdl-cell--${sz_8.intValue()}-col-tablet mdl-cell--${sz_4.intValue()}-col-phone" style= "justify-content: center;" >
"""
		html_ += container
		html_ += """
		</div>
"""
		count++
	}
	html_ += """
	</div>
"""

	return cleanHtml(html_)
}

List hubiForm_help(){
	List container; container=[]
	container << hubiForm_text_input ("Horizontal Axis Format", "graph_h_format", sBLK, true)
	if(graph_h_format){
		Date today=new Date()
		container << hubiForm_text("""<i><small><b>Horizontal Axis Sample:</b> ${today.format(graph_h_format)}</small></i>""")
	}
	container << hubiForm_switch	((sTIT): "Show String Formatting Help", (sNM): "dummy", default: false, submit_on_change: true)
	if((Boolean)settings.dummy){
		List<List<String>> rows=[]
		List<String> header=["<small>Name", "Format", "Result"]
		rows << ["Year", "Y", "2022"]
		rows << ["Month Number", "M", "12"]
		rows << ["Month Name ", "MMM", "Feb"]
		rows << ["Month Full Name", "MMMM", "February"]
		rows << ["Day of Month", "d", "February"]
		rows << ["Day of Week", "EEE", "Mon"]
		rows << ["Day of Week", "EEEE", "Monday"]
		rows << ["Period", "a", "AM/PM"]
		rows << ["Hour (12)", "h", "1..12"]
		rows << ["Hour (12)", "hh", "01..12"]
		rows << ["Hour (24)", "H", "0..23"]
		rows << ["Hour (24)", "HH", "00..23"]
		rows << ["Minute", "m", "0..59"]
		rows << ["Minute", "mm", "00..59"]
		rows << ["Seconds", "s", "0..59"]
		rows << ["Seconds", "ss", "00..59 </small>"]
/*		List val=[]
		val <<"<b>Name"; val << "Format" ; val <<"Result</b>"
		val <<"<small>Year"; val << "Y"; val << "2022"
		val <<"Month Number"; val << "M"; val << "12"
		val <<"Month Name "; val << "MMM"; val << "Feb"
		val <<"Month Full Name"; val << "MMMM"; val << "February"
		val <<"Day of Month"; val << "d"; val << "February"
		val <<"Day of Week"; val << "EEE"; val << "Mon"
		val <<"Day of Week"; val << "EEEE"; val << "Monday"
		val <<"Period"; val << "a"; val << "AM/PM"
		val <<"Hour (12)"; val << "h"; val << "1..12"
		val <<"Hour (12)"; val << "hh"; val << "01..12"
		val <<"Hour (24)"; val << "H"; val << "0..23"
		val <<"Hour (24)"; val << "HH"; val << "00..23"
		val <<"Minute"; val << "m"; val << "0..59"
		val <<"Minute"; val << "mm"; val << "00..59"
		val <<"Seconds"; val << "s"; val << "0..59"
		val <<"Seconds"; val << "ss"; val << "00..59 </small>"
		container << hubiForm_cell(val, 3) */
		container << hubiForm_table([header: header, rows: rows])
		container << hubiForm_text("""<b><small>Example: "EEEE, MMM d, Y hh:mm:ss a" <br>= "Monday, June 6, 2022 08:21:33 AM</small></b>""")
	}
	return container
}

static String hubiForm_table(Map map){

	List<String> header=(List<String>)map.header
	List<List<String>> rows=(List<List<String>>)map.rows
	List<String> footer=map.footer ? (List<String>)map.footer : []

	String html_
	html_="""
	<table class="mdl-data-table  mdl-shadow--2dp dataTable" role="grid" data-upgraded=",MaterialDataTable">
	<thead><tr>
"""
	header.each{ String cell->
		html_ += """			<th class="mdl-data-table__cell--non-numeric ">${cell}</th>"""
	}
	html_ += """
	</tr></thead>
	<tbody>
"""
	//Integer count=0
	rows.each{ List<String> row->

		html_ += """<tr role="row" class="odd">
"""
		row.each{ String cell->
			html_ += """<td class="mdl-data-table__cell--non-numeric">${cell}</td>
"""
		}
		html_ += """</tr>
"""
	} //rows
	html_ += """<tr role="row" class="even">
"""
	footer.each{ String cell->
		html_ += """<td class="mdl-data-table__cell--non-numeric">${cell}</td>
"""
	}
	html_ += """</tr>
"""

	html_ += """	</tbody></table>

"""

	return cleanHtml(html_)
}


static String hubiForm_text(String text, String link=null){

	String html_
	if(link != null){
		html_="""<a href="${link}" target="_blank">${text}</a>"""
	}else{
		html_="""${text}"""
	}

	return html_
}

static String hubiForm_text_format(Map map){

	String text=sMs(map,'text')
	String halign=map.horizontal_align ? "text-align: ${map.horizontal_align};" : ""
	//String valign=map.vertical_align ? "vertical-align: ${map.vertical_align}; " : ""
	String size=map.sz ? "font-size: ${map.sz}px;" : ""
	String html_="""<p style="$halign padding-top:20px; $size">$text</p>"""

	return cleanHtml(html_)
}

static def hubiForm_page_button(String title, String page, String width, String icon){
	String html_

	html_="""
	<button type="button" name="_action_href_${page}|${page}|1" class="btn btn-default btn-lg btn-block hrefElem  mdl-button--raised mdl-shadow--2dp mdl-button__icon" style="text-align:left;width:${width}; margin: 0;">
		<span style="text-align:left;white-space:pre-wrap">
${title}
		</span>
		<ul class="nav nav-pills pull-right">
			<li><i class="material-icons">${icon}</i></li>
		</ul>
		<br>
		<span class="state-incomplete-text " style="text-align: left; white-space:pre-wrap"></span>
	</button>
	"""

	return cleanHtml(html_)
}


def hubiForm_section(String title, Integer pos, String icon, String suffix, Closure code){

	String id=title.replace(' ', '_').replace('(', '').replace(')','')
	String title_=title.replace("'", "").replace("`", "")

	String titleHTML="""
	<div class="mdl-layout__header" style="display: block; background:#033673; margin: 0 -16px; width: calc(100% + 32px); position: relative; z-index: ${pos}; overflow: visible;">
		<div class="mdl-layout__header-row">
			<span class="mdl-layout__title" style="margin-left: -32px; font-size: 18px; width: auto;">
				${title_}
			</span>
		<div class="mdl-layout-spacer"></div>
			<ul class="nav nav-pills pull-right">
				<li> <i class="material-icons">${icon}</i></li>
			</ul>
		</div>
	</div>
"""

	String modContent
	modContent="""
	<div id=${id} style="display: none;"></div>
		<script>
			var sectionElem=jQuery('#${id}').parent();

			/*hide default header*/
			sectionElem.css('display', 'none');
			sectionElem.css('z-index', ${pos});

			var elem=sectionElem.parent().parent();
			elem.addClass('mdl-card mdl-card-wide mdl-shadow--8dp');
			elem.css('width', '100%');
			elem.css('padding', '0 16px');
			elem.css('display', 'block');
			elem.css('min-height', 0);
			elem.css('position', 'relative');
			elem.css('z-index', ${pos});
			elem.css('overflow', 'visible');
			elem.prepend('${titleHTML}');
		</script>
"""

	modContent=cleanHtml(modContent)

	section(modContent, code)
}

String hubiForm_enum(Map map){

	String title=sMs(map,sTIT)
	String var=sMs(map,sNM)
	List<String> list=(List<String>)map.list
	String defaultVal=sMs(map,'default')
	Boolean submit_on_change=map.submit_on_change

	if(settings[var] == null){
		app.updateSetting (var, [(sVAL):defaultVal, (sTYPE):sENUM])
		settings[var]=defaultVal
	}

	String actualVal=settings[var] != null ? "${settings[var]}" : defaultVal
	String submitOnChange=submit_on_change ? "submitOnChange" : sBLK

	String html_
	html_="""
<div class="form-group">
	<input type="hidden" name="${var}.type" value=${sENUM}>
	<input type="hidden" name="${var}.multiple" value="false">
</div>

<div class="mdl-cell mdl-cell--12-col mdl-textfield mdl-js-textfield" style="" data-upgraded=",MaterialTextfield">
	<label for="settings[${var}]" class="control-label">
	<b> ${title} </b>
	</label>

	<select id="settings[${var}]" name="settings[${var}]" class="selectpicker form-control mdl-switch__input ${submitOnChange} SumoUnder" placeholder="Click to set" data-default="${defaultVal}" tabindex="-1">
		<option class="optiondefault" value="" style="display: block;">No selection</option>
"""
	String selectedStringS
	list.each{ String item ->
		String selectedString
		if(actualVal == item){
			selectedString = / selected="selected"/
			selectedStringS = item
		}else
			selectedString=sBLK

		html_ += """
		<option value="${item}"${selectedString}>${item}</option>"""
	}
	html_ += """
	</select>
"""

/*
	html_ += """
	<div class="optWrapper">
		<ul class="options">
			<li class="opt optiondefault"><label>No selection</label></li>
"""
	list.each{ String item ->
		html_ += actualVal==item ? """<li class="opt selected"><label>${item}</label></li>""" : """<li class="opt"><label>${item}</label></li>"""
	}
	html_ += """
		</ul>
	</div>
"""
 */

	html_ += """
</div>

	"""

	return cleanHtml(html_)
}

String hubiForm_switch(Map map){

	String title=sMs(map,sTIT)
	String var=sMs(map,sNM)
	Boolean defaultVal=map.default
	Boolean submit_on_change=map.submit_on_change

	if(settings[var]==null){
		app.updateSetting (var, !!defaultVal)
		settings[var]= !!defaultVal
	}

	Boolean actualVal=settings[var] != null ? settings[var] : defaultVal
	String submitOnChange=submit_on_change ? "submitOnChange" : sBLK

	String html_="""
	<div class="form-group">
		<input type="hidden" name="${var}.type" value=${sBOOL}>
		<input type="hidden" name="${var}.multiple" value="false">
	</div>
	<label for="settings[${var}]" class="mdl-switch mdl-js-switch mdl-js-ripple-effect mdl-js-ripple-effect--ignore-events is-upgraded ${actualVal ? "is-checked" : ""} data-upgraded=",MaterialSwitch,MaterialRipple">
		<input name="checkbox[${var}]" id="settings[${var}]" class="mdl-switch__input ${submitOnChange}" type="checkbox" ${actualVal ? "checked" : ""}>
			<div class="mdl-switch__label" >${title}</div>
			<div class="mdl-switch__track"></div>
			<div class="mdl-switch__thumb">
				<span class="mdl-switch__focus-helper">
				</span>
			</div>
			<span class="mdl-switch__ripple-container mdl-js-ripple-effect mdl-ripple--center" data-upgraded=",MaterialRipple">
				<span class="mdl-ripple">
				</span>
			</span>
	</label>
	<input name="settings[${var}]" type="hidden" value="${actualVal}">

"""

	return cleanHtml(html_)
}

String hubiForm_text_input(String title, String ivar, String defaultVal, Boolean submitOnChange){

	String var=ivar.toString()

	if(settings[var] == null){
		app.updateSetting(var, defaultVal)
		settings[var]=defaultVal
	}
	//settings[var]=settings[var] != null ? settings[var] : defaultVal

	String html_="""
	<div class="form-group">
		<input type="hidden" name="${var}.type" value="text">
		<input type="hidden" name="${var}.multiple" value="false">
	</div>
	<label for="settings[${var}]" class="control-label">${title}</label>
	<input type="text" name="settings[${var}]"
		class="mdl-textfield__input ${submitOnChange ? "submitOnChange" : ""} "
		value="${settings[var]}" placeholder="Click to set" id="settings[${var}]">
	"""

	return cleanHtml(html_)
}

String hubiForm_font_size(Map map){

	String title=sMs(map,sTIT)
	String varname=sMs(map,sNM)
	Integer default_=iMs(map,'default')
	Integer min=iMs(map,'min')
	Integer max=iMs(map,'max')
	Boolean submit_on_change=map.submit_on_change
	String baseId=varname

	String varFontSize="${varname}_font"
	if(!settings[varFontSize]){
		app.updateSetting(varFontSize, default_)
		settings[varFontSize]=default_
	}

	String submitOnChange=submit_on_change ? "submitOnChange" : sBLK

	String html_ =
			"""
	<table style="width:100%">
		<tr><td><label for="settings[${varFontSize}]" class="control-label"><b>${title} Font Size</b></td>
			<td >
				<span id="${baseId}_font_size_val" style="text-align:right; font-size:${settings[varFontSize]}px">Font Size: ${settings[varFontSize]}</span>
			</td>
				</label>
		</tr>
	</table>
	<input type="range" min="$min" max="$max" name="settings[${varFontSize}]"
					class="mdl-slider $submitOnChange "
					value="${settings[varFontSize]}"
					id="settings[${varFontSize}]"
					onchange="${baseId}_updateFontSize(this.value);">
	<div class="form-group">
			<input type="hidden" name="${varFontSize}.type" value="number">
			<input type="hidden" name="${varFontSize}.multiple" value="false">
	</div>
	<script>
		function ${baseId}_updateFontSize(val){
				var text="";
				text += "Font Size: "+val;
				jQuery('#${baseId}_font_size_val').css("font-size", val+"px");
				jQuery('#${baseId}_font_size_val').text(text);
		}
	</script>
	"""

	return cleanHtml(html_)
}

String hubiForm_fontvx_size(Map map){

	String title=sMs(map,sTIT)
	String varname=sMs(map,sNM)
	Integer default_=iMs(map,'default')
	Integer min=iMs(map,'min')
	Integer max=iMs(map,'max')
	Boolean submit_on_change=map.submit_on_change
	String baseId=varname
	String weight=map.weight ? "font-weight: ${map.weight} !important;" : sBLK
	String icon
	icon=sNL

	String varFontSize="${varname}_font"
	Integer icon_size=settings[varFontSize] ? 10*(Integer)settings[varFontSize] : default_*10

	String jq

	if(map.icon){
		icon="""
			<style>
				.material-icons.test{ font-size: ${icon_size}px; }
			</style>
			<i id="${baseId}_icon" class="material-icons test">cloud</i>
		"""

		jq="""jQuery('.test').css('font-size', 10*val+"px");
"""
	}else{
		jq="""
			jQuery('#${baseId}_font_size_val').css("font-size", 0.5*val+"em");
			jQuery('#${baseId}_font_size_val').text(text);
		"""
	}

	if(!settings[varFontSize]){
		app.updateSetting(varFontSize, default_)
		settings[varFontSize]=default_
	}

	String submitOnChange=submit_on_change ? "submitOnChange" : sBLK

	String html_
	html_ =
			"""
	<label for="settings[${varFontSize}]" class="control-label" style= "vertical-align: bottom;">
		<b>${title}</b>
		<span id="${baseId}_font_size_val" style="float:right; font-size: ${settings[varFontSize]*0.5}em; ${weight}">
			${icon == sNL ? settings[varFontSize] : icon}
		</span>
	</label>

	<input type="range" min="$min" max="$max" name="settings[${varFontSize}]"
					class="mdl-slider $submitOnChange "
					value="${settings[varFontSize]}"
					id="settings[${varFontSize}]"
					onchange="${baseId}_updateFontSize(this.value);">
	<div class="form-group">
			<input type="hidden" name="${varFontSize}.type" value="number">
			<input type="hidden" name="${varFontSize}.multiple" value="false">
	</div>
	<script>
		function ${baseId}_updateFontSize(val){
				var text="";
				text += val;"""
	html_+= jq
	html_+="""

		}
	</script>
	"""

	return cleanHtml(html_)
}


String hubiForm_line_size(Map map){

	String title=sMs(map,sTIT)
	String varname=sMs(map,sNM)
	Integer default_=iMs(map,'default')
	Integer min=iMs(map,'min')
	Integer max=iMs(map,'max')
	Boolean submit_on_change=map.submit_on_change
	String baseId=varname

	String varLineSize="${varname}_line_size"
	if(!settings[varLineSize]){
		app.updateSetting(varLineSize, default_)
		settings[varLineSize]=default_
	}

	String submitOnChange=submit_on_change ? "submitOnChange" : sBLK

	String html_ =
			"""
	<table style="width:100%">
		<tr><td><label for="settings[${varLineSize}]" class="control-label"><b>${title} Width</b></td>
			<td border=1 style="text-align:right;">
				<span id="${baseId}_line_size_text" name="testing" >
						Width: ${settings[varLineSize]} <hr id='${baseId}_line_size_draw' style='background-color:#1A77C9; height:${settings[varLineSize]}px; border: 0;'>
				</span>
		</td>
				</label>
		</tr>
	</table>
	<input type="range" min="$min" max="$max" name="settings[${varLineSize}]"
					class="mdl-slider ${submitOnChange}"
					value="${settings[varLineSize]}"
					id="settings[${varLineSize}]"
					onchange="${baseId}_updateLineInput(this.value);">
	<div class="form-group">
			<input type="hidden" name="${varLineSize}.type" value="number">
			<input type="hidden" name="${varLineSize}.multiple" value="false">
	</div>
	<script>
		function ${baseId}_updateLineInput(val){
				var text="";
				text += "Width: "+val;

				jQuery('#${baseId}_line_size_text').text(text);
				jQuery('#${baseId}_line_size_draw').remove();
				jQuery('#${baseId}_line_size_text').after("<hr id='${baseId}_line_size_draw' style='background-color:#1A77C9; height:"+val+"px; border: 0;'>");
		}
	</script>
	"""

	return cleanHtml(html_)

}

String hubiForm_slider(Map map){

	String title=sMs(map,sTIT)
	String varname=sMs(map,sNM)
	Integer default_=iMs(map,'default')
	Integer min=iMs(map,'min')
	Integer max=iMs(map,'max')
	String units=sMs(map,'units')
	Boolean submit_on_change=map.submit_on_change

	//def fontSize
	String varSize=varname
	String baseId=varname

	//settings[varSize]=settings[varSize] ? settings[varSize] : default_
	if(!settings[varSize]){
		settings[varSize]=default_
		app.updateSetting(varSize, default_)
	}

	String submitOnChange=submit_on_change ? "submitOnChange" : sBLK

	String html_ = """
	<table style="width:100%">
		<tr>
			<td>
				<label for="settings[${varSize}]" class="control-label"><b>${title}</b>
			</td>
			<td border=1 style="text-align:right;"><span id="${baseId}_slider_val" name="testing" >${settings[varSize]}${units}</span></td>
				</label>
		</tr>
	</table>
	<input type="range" min="$min" max="$max" name="settings[${varSize}]"
				class="mdl-slider $submitOnChange "
				value="${settings[varSize]}"
				id="settings[${varSize}]"
				onchange="${baseId}_updateTextInput(this.value);">
	<div class="form-group">
			<input type="hidden" name="${varSize}.type" value="number">
			<input type="hidden" name="${varSize}.multiple" value="false">
	</div>
	<script>

			function ${baseId}_updateTextInput(val){
				var text="";
				text += val+"${units}";
				jQuery('#${baseId}_slider_val').text(text);
			}
	</script>
	"""

	return cleanHtml(html_)
}

String hubiForm_color(String title, String varname, String defaultColorValue, Boolean defaultTransparentValue, Boolean submit=false){

	String varnameColor="${varname}_color"
	String varnameTransparent="${varname}_color_transparent"
	String colorTitle="<b>${title} Color</b>"
	String notTransparentTitle="Transparent"
	String transparentTitle="${title}: Transparent"

	String curColor= settings[varnameColor] ?: defaultColorValue
	//settings[varnameColor]= settings[varnameColor] ?: defaultColorValue
	if(!settings[varnameColor]) app.updateSetting(varnameColor, defaultColorValue)

	Boolean curTransparent= settings[varnameTransparent]!=null ? settings[varnameTransparent] : defaultTransparentValue
	//settings[varnameTransparent]=settings[varnameTransparent] ?: defaultTransparentValue
	if(settings[varnameTransparent]!=curTransparent) app.updateSetting(varnameTransparent, curTransparent)

	Boolean isTransparent=curTransparent

	String html_ = """
	<div style="display: flex; flex-flow: row wrap;">
		<div style="display: flex; flex-flow: row nowrap; flex-basis: 100%;">
			${!isTransparent ? """<label for="settings[${varnameColor}]" class="control-label" style="flex-grow: 1">${colorTitle}</label>""" : """"""}
			<label for="settings[${varnameTransparent}]" class="control-label" style="width: auto;">${isTransparent ? transparentTitle: notTransparentTitle}</label>
		</div>
			${!isTransparent ? """
		<div style="flex-grow: 1; flex-basis: 1px; padding-right: 8px;">
			<input type="color" name="settings[${varnameColor}]" class="mdl-textfield__input ${submit ? "submitOnChange" : ""} " value="${curColor}" placeholder="Click to set" id="settings[${varnameColor}]" list="presetColors">
			<datalist id="presetColors">
				<option>#800000</option>
				<option>#FF0000</option>
				<option>#FFA500</option>
				<option>#FFFF00</option>

				<option>#808000</option>
				<option>#008000</option>
				<option>#00FF00</option>

				<option>#800080</option>
				<option>#FF00FF</option>

				<option>#000080</option>
				<option>#0000FF</option>
				<option>#00FFFF</option>

				<option>#FFFFFF</option>
				<option>#C0C0C0</option>
				<option>#000000</option>
			</datalist>
		</div>
""" : ""}
		<div class="submitOnChange">
			<input name="checkbox[${varnameTransparent}]" id="settings[${varnameTransparent}]" style="width: 27.6px; height: 27.6px;" type="checkbox" onmousedown="((e) =>{ jQuery('#${varnameTransparent}').val('${!isTransparent}'); })()" ${isTransparent ? 'checked' : ''} />
			<input id="${varnameTransparent}" name="settings[${varnameTransparent}]" type="hidden" value="${isTransparent}" />
		</div>
		<div class="form-group">
			<input type="hidden" name="${varnameColor}.type" value="color">
			<input type="hidden" name="${varnameColor}.multiple" value="false">

			<input type="hidden" name="${varnameTransparent}.type" value=${sBOOL}>
			<input type="hidden" name="${varnameTransparent}.multiple" value="false">
		</div>
	</div>
"""

	return cleanHtml(html_)
}

String hubiForm_graph_preview(){

//	if(!state.count_) state.count_=7

	String html_ = """
	<style>
		.iframe-container{
			overflow: hidden;
			width: 45vmin;
			height: 45vmin;
			position: relative;
		}

		.iframe-container iframe{
			border: 0;
			left: 0;
			position: absolute;
			top: 0;
		}
	</style>
	<div class="iframe-container">
	<iframe id="preview_frame" style="width: 100%; height: 100%; position: relative; z-index: 1; background-image: url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAEq2lUWHRYTUw6Y29tLmFkb2JlLnhtcAAAAAAAPD94cGFja2V0IGJlZ2luPSLvu78iIGlkPSJXNU0wTXBDZWhpSHpyZVN6TlRjemtjOWQiPz4KPHg6eG1wbWV0YSB4bWxuczp4PSJhZG9iZTpuczptZXRhLyIgeDp4bXB0az0iWE1QIENvcmUgNS41LjAiPgogPHJkZjpSREYgeG1sbnM6cmRmPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5LzAyLzIyLXJkZi1zeW50YXgtbnMjIj4KICA8cmRmOkRlc2NyaXB0aW9uIHJkZjphYm91dD0iIgogICAgeG1sbnM6ZXhpZj0iaHR0cDovL25zLmFkb2JlLmNvbS9leGlmLzEuMC8iCiAgICB4bWxuczp0aWZmPSJodHRwOi8vbnMuYWRvYmUuY29tL3RpZmYvMS4wLyIKICAgIHhtbG5zOnBob3Rvc2hvcD0iaHR0cDovL25zLmFkb2JlLmNvbS9waG90b3Nob3AvMS4wLyIKICAgIHhtbG5zOnhtcD0iaHR0cDovL25zLmFkb2JlLmNvbS94YXAvMS4wLyIKICAgIHhtbG5zOnhtcE1NPSJodHRwOi8vbnMuYWRvYmUuY29tL3hhcC8xLjAvbW0vIgogICAgeG1sbnM6c3RFdnQ9Imh0dHA6Ly9ucy5hZG9iZS5jb20veGFwLzEuMC9zVHlwZS9SZXNvdXJjZUV2ZW50IyIKICAgZXhpZjpQaXhlbFhEaW1lbnNpb249IjIiCiAgIGV4aWY6UGl4ZWxZRGltZW5zaW9uPSIyIgogICBleGlmOkNvbG9yU3BhY2U9IjEiCiAgIHRpZmY6SW1hZ2VXaWR0aD0iMiIKICAgdGlmZjpJbWFnZUxlbmd0aD0iMiIKICAgdGlmZjpSZXNvbHV0aW9uVW5pdD0iMiIKICAgdGlmZjpYUmVzb2x1dGlvbj0iNzIuMCIKICAgdGlmZjpZUmVzb2x1dGlvbj0iNzIuMCIKICAgcGhvdG9zaG9wOkNvbG9yTW9kZT0iMyIKICAgcGhvdG9zaG9wOklDQ1Byb2ZpbGU9InNSR0IgSUVDNjE5NjYtMi4xIgogICB4bXA6TW9kaWZ5RGF0ZT0iMjAyMC0wNi0wMlQxOTo0NzowNS0wNDowMCIKICAgeG1wOk1ldGFkYXRhRGF0ZT0iMjAyMC0wNi0wMlQxOTo0NzowNS0wNDowMCI+CiAgIDx4bXBNTTpIaXN0b3J5PgogICAgPHJkZjpTZXE+CiAgICAgPHJkZjpsaQogICAgICBzdEV2dDphY3Rpb249InByb2R1Y2VkIgogICAgICBzdEV2dDpzb2Z0d2FyZUFnZW50PSJBZmZpbml0eSBQaG90byAxLjguMyIKICAgICAgc3RFdnQ6d2hlbj0iMjAyMC0wNi0wMlQxOTo0NzowNS0wNDowMCIvPgogICAgPC9yZGY6U2VxPgogICA8L3htcE1NOkhpc3Rvcnk+CiAgPC9yZGY6RGVzY3JpcHRpb24+CiA8L3JkZjpSREY+CjwveDp4bXBtZXRhPgo8P3hwYWNrZXQgZW5kPSJyIj8+IC4TuwAAAYRpQ0NQc1JHQiBJRUM2MTk2Ni0yLjEAACiRdZE7SwNBFEaPiRrxQQQFLSyCRiuVGEG0sUjwBWqRRPDVbDYvIYnLboIEW8E2oCDa+Cr0F2grWAuCoghiZWGtaKOy3k2EBIkzzL2Hb+ZeZr4BWyippoxqD6TSGT0w4XPNLyy6HM/UYqONfroU1dBmguMh/h0fd1RZ+abP6vX/uYqjIRI1VKiqEx5VNT0jPCk8vZbRLN4WblUTSkT4VLhXlwsK31p6uMgvFseL/GWxHgr4wdYs7IqXcbiM1YSeEpaX404ls+rvfayXNEbTc0HJnbI6MAgwgQ8XU4zhZ4gBRiQO0YdXHBoQ7yrXewr1s6xKrSpRI4fOCnESZOgVNSvdo5JjokdlJslZ/v/11YgNeovdG31Q82Sab93g2ILvvGl+Hprm9xHYH+EiXapfPYDhd9HzJc29D84NOLssaeEdON+E9gdN0ZWCZJdli8Xg9QSaFqDlGuqXip797nN8D6F1+aor2N2DHjnvXP4Bhcln9Ef7rWMAAAAJcEhZcwAACxMAAAsTAQCanBgAAAAXSURBVAiZY7hw4cL///8Z////f/HiRQBMEQrfQiLDpgAAAABJRU5ErkJggg=='); background-size: 25px; background-repeat: repeat; image-rendering: pixelated;" src="${state.localEndpointURL}graph/?access_token=${state.endpointSecret}" data-fullscreen="false"
		onload="(() =>{
	})()""></iframe>
	</div>
	"""
	return cleanHtml(html_)

}

static String hubiForm_sub_section(String myText=""){

	String id=myText.replaceAll("[^a-zA-Z0-9]", sBLK)
	String newText=myText.replaceAll("'", "").replaceAll("`", "")
	String html_="""
		<div class="mdl-layout__header" style="display: block; min-height: 0;">
			<div class="mdl-layout__header-row" style="height: 48px;">
				<span class="mdl-layout__title" style="margin-left: -32px; font-size: 9px; width: auto;">
					<h4 id="${id}" style="font-size: 16px;">${newText}</h4>
				</span>
			</div>
		</div>
"""

	return cleanHtml(html_)
}
/*
static String hubiForm_cell(List containers, Integer numPerRow){

	String html_
	html_ = """
		<div class="mdl-grid mdl-grid--no-spacing mdl-shadow--4dp" style="margin-top: 0px !important; margin: 0px; padding: 0px 0px;">
"""
	containers.each{container->
		html_ += """
			<div class="mdl-cell mdl-cell--${12/numPerRow}-col-desktop mdl-cell--${8/numPerRow}-col-tablet mdl-cell--${4/numPerRow}-col-phone">
"""
		html_ += container
		html_ += """
			</div>
"""
	}
	html_ += """
		</div>
"""

	return cleanHtml(html_)
} */

def hubiForm_list_reorder(String var, String var_color, String solid_background=""){

	Boolean result_; result_=null

//	TODO
	List<Map> dataSources=gtDataSources()

	String v=sMs(settings,var)
	if(v){
		List<Map> list_=hubiTools_get_order(v)

		//Check List
		result_=hubiTools_check_list(dataSources, list_)
	}

	String nres
	nres=sMs(settings,var)
	if(!result_ && var){
		settings["${var}"]=null
		nres=sNL
		wremoveSetting(var)
	}

	//build list order

	//Setup Original Ordering
	if(nres==sNL){
		nres="["
		//settings["${var}"]="["
//	TODO
		if(dataSources){
			Integer count_; count_=0
			for(Map ent in dataSources){

				String sid=sMs(ent,sID)
				String attribute=sMs(ent,sA)
				//settings["${var}"] += /"attribute_${sid}_${attribute}",/
				nres += ((nres.length()>1 ? ',' : '') + /"attribute_${sid}_${attribute}"/ )
				String tvar= "attribute_${sid}_${attribute}_${var_color}_color".toString()
				if(settings[tvar] == null){
					String cl='color'
					if(solid_background== sBLK){
						String c= hubiTools_rotating_colors(count_)
						settings[tvar]=c
						settingUpdate(tvar, c, cl)
					}else{
						settings[tvar]=solid_background
						app.updateSetting(tvar, solid_background, cl)
					}
				}
				count_++
			}
		}
		//settings["${var}"]=settings["${var}"].substring(0, settings["${var}"].length() - 1)
		nres== nres.substring(0, nres.length() - 1)
		//settings["${var}"] += "]"
		nres += "]"
		settings["${var}"]=nres
		app.updateSetting(var, nres)
	}

	List<Map> list_data=[]
	List<Map> order_=hubiTools_get_order(nres)
	String title_
	for(Map device_ in order_){
		String deviceName_=hubiTools_get_name_from_id(sMs(device_,sID))
		title_="""<b>${deviceName_}</b><br><p style="float: right;">${device_[sATTR]}</p>"""
		title_.replace("'", "").replace("`", "")
		list_data << [(sTIT): title_, var: "attribute_${device_[sID]}_${device_[sATTR]}"]
	}

	String var_val_=nres.replace('"', '&quot;')
	String html_
	html_="""
		<script>
			function onOrderChange(order){
				jQuery("#settings${var}").val(JSON.stringify(order))
			}
		</script>
		<script src="/local/${isSystemType() ? 'webcore/' : ''}a930f16d-d5f4-4f37-b874-6b0dcfd47ace-HubiGraph.js"></script>
		<div id="moveable" class="mdl-grid" style="margin: 0; padding: 0; text-color: white !important">
"""

	for(Map data in list_data){
		String color_=settings["${data.var}_${var_color}_color"]
		String id_="${data.var}"
		html_ += """<div id="$id_" class="mdl-cell mdl-cell--12-col-desktop mdl-cell--8-col-tablet mdl-cell--4-col-phone mdl-shadow--4dp mdl-color-text--indigo-400"
						draggable="true" ondragover="dragOver(event)" ondragstart="dragStart(event)" ondragend= "dragEnd(event)"
						style="font-size: 16px !important; margin: 8px !important; padding: 14px !important;">
						<i class="mdl-icon-toggle__label material-icons" style="color: ${color_} !important;">fiber_manual_record</i>

"""
		html_ += sMs(data,sTIT)
		html_ += """</div>
"""
	}
	html_ += """</div>
		<input type="text" id="settings${var}" name="settings[${var}]" value="${var_val_}" style="display: none;" disabled />
		<div class="form-group">
			<input type="hidden" name="${var}.type" value="text">
			<input type="hidden" name="${var}.multiple" value="false">
		</div>
"""

	paragraph cleanHtml(html_)
}



/** Tools */

void hubiTool_create_tile(){

	if(isInf()) info "Checking webCoRE Child Tile Device",null,iN2

	String dname
	dname=device_name
	if(!dname){
		dname= app_name ?: tDesc()
		dname += ' Tile'
	}

	def childDevice
	childDevice=getChildDevice("webCoRE_${app.id}")
	if(!childDevice){
		if(isDbg()) debug "Creating Device $dname",null,iN2
		childDevice=addChildDevice("ady624", "webCoRE Graphs Tile Device", "webCoRE_${app.id}", null,[completedSetup: true, label: dname])
		if(childDevice) info "Created HTTP Switch [${childDevice}]",null

	}
	else{

		if(childDevice.label!=dname){
			childDevice.label=dname
			if(isDbg())debug "Device Label Updated to [${dname}]",null,iN2
		}
	}

	//Send the html
	String s= "${state.localEndpointURL}graph/?access_token=${state.endpointSecret}"
	childDevice.setGraph(s)
	if(isDbg())debug "Sent setGraph: ${s}",null,iN2
}

void hubiTools_validate_order(List<String> all){
	if(isEric())myDetail null,"_validate_order $all",i1

	List order
	order=[]
	List<Map> dataSources=gtDataSources()

	if(dataSources){
		for(Map ent in dataSources){

			// TODO need to include attribute to make unique

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)
			String sa="${sid}_${attribute}".toString()
			String varn='displayOrder_'+sa
			order << settings[varn]
		}
	}
	if(isEric())myDetail null,"_validate_order $order",iN2

	//if we are initialized and need to check
	if(isEric())myDetail null,"_validate_order ${state.lastOrder}",iN2

	if(state.lastOrder && ((List)state.lastOrder)[0]){
		List remains=all.findAll{ String it -> !order.contains(it) }
		List dupes=[]

		order.each{ ord ->
			if(order.count(ord) > 1) dupes << ord
		}

		if(dataSources){
			Integer idx; idx=0
			for(Map ent in dataSources){
				String sid=sMs(ent,sID)
				String attribute=sMs(ent,sA)
				String sa="${sid}_${attribute}".toString()
				String varn='displayOrder_'+sa
				if(((List)state.lastOrder)[idx] == order[idx] && dupes.contains(settings[varn])){
					settings[varn]=remains[0]
					app.updateSetting(varn, [(sVAL): remains[0], (sTYPE): sENUM])
					remains.removeAt(0)
				}
				idx++
			}
		}
	}

	//reconstruct order
	order=[]
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)
			String sa="${sid}_${attribute}".toString()
			String varn='displayOrder_'+sa
			order << settings[varn]
		}
	}
	if(isEric())myDetail null,"_validate_order $order"
	state.lastOrder=order
}

static String hubiTools_rotating_colors(Integer c){

	String ret="#FFFFFF" // WHITE
	Integer color=c % 13
	switch (color){
		case 0: return hubiTools_get_color_code("RED")
		case 1: return hubiTools_get_color_code("GREEN")
		case 2: return hubiTools_get_color_code("BLUE")
		case 3: return hubiTools_get_color_code("MAROON")
		case 4: return hubiTools_get_color_code("YELLOW")
		case 5: return hubiTools_get_color_code("OLIVE")
		case 6: return hubiTools_get_color_code("AQUA")
		case 7: return hubiTools_get_color_code("LIME")
		case 8: return hubiTools_get_color_code("NAVY")
		case 9: return hubiTools_get_color_code("FUCHSIA")
		case 10: return hubiTools_get_color_code("PURPLE")
		case 11: return hubiTools_get_color_code("TEAL")
		case 12: return hubiTools_get_color_code("ORANGE")
	}
	return ret
}

static String hubiTools_get_color_code(String input_color){

	String new_color=input_color.toUpperCase()
	switch (new_color){

		case "WHITE" :	return "#FFFFFF"
		case "SILVER" :	return "#C0C0C0"
		case "GRAY" :	return "#808080"
		case "BLACK" :	return "#000000"

		case "RED" :	return "#FF0000"
		case "GREEN" :	return "#008000"
		case "BLUE" :	return "#0000FF"
		case "MAROON" :	return "#800000"
		case "YELLOW" :	return "#FFFF00"
		case "OLIVE" :	return "#808000"
		case "AQUA" :	return "#00FFFF"
		case "LIME" :	return "#00FF00"
		case "NAVY" :	return "#000080"
		case "FUCHSIA" :return "#FF00FF"
		case "PURPLE" :	return "#800080"
		case "TEAL" :	return "#008080"
		case "ORANGE" :	return "#FFA500"
	}
	return 'error_color_code'
}

String hubiTools_get_name_from_id(String id){ //, sensors){
	String return_val
	return_val="Error"
//	TODO
	List<Map> dataSources=gtDataSources()
	if(dataSources){
		for(Map ent in dataSources){
			if(id == sMs(ent,sID)){
				return_val=sMs(ent,sDISPNM)
				break
			}
		}
	}
	return return_val
}

List<Map> hubiTools_get_order(String order){
	if(isEric())myDetail(null,"_get_order ${order}",i1)
	List<String> split_=order.replace('"', '').replace('[', '').replace(']', '').replace("attribute_", sBLK).split(',')
	List<Map> list_=[]
	split_.each{ String device->
		List<String> sub_=device.split('_')
		list_ << [(sID): sub_[0], (sATTR):sub_[1]]
	}
	if(isEric())myDetail null,"_get_order $order $list_"
	return list_
}

Boolean hubiTools_check_list(List<Map> dataSources, List<Map> list_){
	if(isEric())myDetail null,"_check_list $dataSources $list_",i1

	Boolean result
	result=true
	Integer count_; count_=0
	Boolean inner_result
	//check for addition/changes
	if(dataSources){
		for(Map ent in dataSources){

			String sid=sMs(ent,sID)
			String attribute=sMs(ent,sA)

			count_++
			inner_result=false
			Integer i
			for(i=0; i<list_.size(); i++){
				if(sMs(list_[i],sID) == sid && sMs(list_[i],sATTR) == attribute){
					inner_result=true
					break
				}
			}
			result=result && inner_result
		}
	}

	//check for smaller
	Boolean count_result
	count_result=false
	if(list_.size() == count_){
		count_result=true
	}
	if(isEric())myDetail null,"_check_list $result $count_result"
	return (result && count_result)
}










// TODO: Keep updated

@Field static final String sNL=(String)null
@Field static final String sSNULL='null'
@Field static final String sBOOLN='boolean'
@Field static final String sBLK=''
@Field static final String sCOMMA=','
@Field static final String sSPC=' '

@Field static final String sNM='name'
@Field static final String sID='id'
@Field static final String sICON='icon'
@Field static final String sREQ='required'
@Field static final String sTYPE='type'
@Field static final String sTIT='title'
@Field static final String sVAL='value'
@Field static final String sERROR='error'
@Field static final String sINFO='info'
@Field static final String sWARN='warn'
@Field static final String sTRC='trace'
@Field static final String sDBG='debug'
@Field static final String sON='on'
@Field static final String sOFF='off'
@Field static final String sSWITCH='switch'

@Field static final String s0='0'
@Field static final String s1='1'
@Field static final String s2='2'
@Field static final Integer iZ=0
@Field static final Integer i1=1
@Field static final Integer i2=2
@Field static final Integer i3=3
@Field static final Integer i4=4
@Field static final Integer i5=5
@Field static final Integer i6=6
@Field static final Integer i7=7
@Field static final Integer i8=8
@Field static final Integer i9=9
@Field static final Integer i10=10
@Field static final Integer i12=12
@Field static final Integer i20=20

@Field static final Long lZ=0L

@Field static final Integer iN1=-1
@Field static final Integer iN2=-2

private static TimeZone mTZ(){ return TimeZone.getDefault() } // (TimeZone)location.timeZone

import java.text.SimpleDateFormat

@CompileStatic
static String formatTime(Date t){
	return dateTimeFmt(t, "yyyy-MM-dd HH:mm:ss.SSS", true)
}

@CompileStatic
static String dateTimeFmt(Date dt, String fmt, Boolean tzChg=true){
	SimpleDateFormat tf = new SimpleDateFormat(fmt)
	if(tzChg && mTZ()){ tf.setTimeZone(mTZ()) }
	return tf.format(dt)
}


/** DEBUG FUNCTIONS		*/

private Boolean isDbg(){ (Integer)state[sLOGNG]>i2 }
private Boolean isTrc(){ (Integer)state[sLOGNG]>i1 }
private Boolean isInf(){ (Integer)state[sLOGNG]>iZ }

private void myDetail(Map r9,String msg,Integer shift=iN1){ Map a=log(msg,r9,shift,null,sWARN,true,false) }

@Field static final String sTMSTMP='timestamp'
@Field static final String sDBGLVL='debugLevel'
@Field static final String sLOGNG='logging'
@Field static final String sLOGS='logs'
@Field static final String sTIMER='timer'

@Field static final String sENUM='enum'

@Field static final String sA='a'
@Field static final String sB='b'
@Field static final String sC='c'
@Field static final String sD='d'
@Field static final String sE='e'
@Field static final String sI='i'
@Field static final String sM='m'
@Field static final String sN='n'
@Field static final String sO='o'
@Field static final String sP='p'
@Field static final String sS='s'
@Field static final String sT='t'
@Field static final String sV='v'

@Field static final Double d1=1.0D

private Map log(message,Map r9,Integer shift=iN2,Exception err=null,String cmd=sNL,Boolean force=false,Boolean svLog=true){
	if(cmd==sTIMER){
		return [(sM):message.toString(),(sT):wnow(),(sS):shift,(sE):err]
	}
	String myMsg
	Exception merr
	merr=err
	Integer mshift
	mshift=shift
	if(message instanceof Map){
		mshift=(Integer)message.s
		merr=(Exception)message.e
		myMsg=sMs(message,sM)+" (${elapseT(lMt(message))}ms)".toString()
	}else myMsg=message.toString()
	String mcmd=cmd!=sNL ? cmd:sDBG

	Integer level
	level=state[sDBGLVL] ? (Integer)state[sDBGLVL]:iZ
	//shift is
	// 0 initialize level,level set to 1
	// 1 start of routine,level up
	// -1 end of routine,level down
	// anything else: nothing happens
//	Integer maxLevel=4

	String ss='╔'
	String sb='║'
	String se='╚'
	String prefix
	prefix=sb
	String prefix2
	prefix2=sb
//	String pad=sBLK //"░"
	switch(mshift){
		case iZ:
			level=iZ
		case i1:
			level+=i1
			prefix=se
			prefix2=ss
//			pad="═"
			break
		case iN1:
			level-=i1
//			pad='═'
			prefix=ss
			prefix2=se
			break
	}
	if(level>iZ){
		prefix=prefix.padLeft(level+(mshift==iN1 ? i1:iZ),sb)
		prefix2=prefix2.padLeft(level+(mshift==iN1 ? i1:iZ),sb)
	}

	state[sDBGLVL]=level

	Boolean hasErr=(merr!=null && !!merr)
	myMsg=myMsg.replaceAll(/(\r\n|\r|\n|\\r\\n|\\r|\\n)+/,"\r")
	if(myMsg.size()>1024){
		myMsg=myMsg[iZ..1023]+'...[TRUNCATED]'
	}
	List<String> msgs=!hasErr ? myMsg.tokenize("\r"):[myMsg]
	if(r9 && r9[sTMSTMP]){
		if(svLog && r9[sLOGS] instanceof List){
			for(String msg in msgs){
				Boolean a=((List)r9[sLOGS]).push([(sO):elapseT(lMs(r9,sTMSTMP)),(sP):prefix2,(sM):msg+(hasErr ? " $merr".toString():sBLK),(sC):mcmd])
			}
		}
	}
	String myPad=sSPC
	if(hasErr) myMsg+="$merr".toString()
	if((mcmd in [sERROR,sWARN]) || hasErr || force || !svLog || !r9 || r9Is(r9,'logsToHE') || isEric())doLog(mcmd, myPad+prefix+sSPC+myMsg)
	//}else log."$mcmd" myMsg
	return [:]
}

void doLog(String mcmd, String msg){
	String clr
	switch(mcmd){
		case sINFO:
			clr= '#0299b1'
			break
		case sTRC:
			clr= sCLRGRY
			break
		case sDBG:
			clr= 'purple'
			break
		case sWARN:
			clr= sCLRORG
			break
		case sERROR:
		default:
			clr= sCLRRED
	}
	String myMsg= msg.replaceAll(sLTH, '&lt;').replaceAll(sGTH, '&gt;')
	log."$mcmd" span(myMsg,clr)
}

private void info(message,Map r9,Integer shift=iN2,Exception err=null){ Map a=log(message,r9,shift,err,sINFO)}
private void trace(message,Map r9,Integer shift=iN2,Exception err=null){ Map a=log(message,r9,shift,err,sTRC)}
private void debug(message,Map r9,Integer shift=iN2,Exception err=null){ Map a=log(message,r9,shift,err,sDBG)}
private void warn(message,Map r9,Integer shift=iN2,Exception err=null){ Map a=log(message,r9,shift,err,sWARN)}
private void error(message,Map r9,Integer shift=iN2,Exception err=null){
	String aa
	aa=sNL
	String bb
	bb=sNL
	try{
		if(err){
			aa=getExceptionMessageWithLine(err)
			bb=getStackTrace(err)
		}
		Map a=log(message,r9,shift,err,sERROR)
	}catch(ignored){}
	if(aa||bb)log.error tDesc()+" exception: "+aa+" \n"+bb
}
//error "object: ${describeObject(e)}",r9

@CompileStatic
private static Long lMs(Map m,String v){ (Long)m[v] }
@CompileStatic
private static Long lMt(Map m){ (Long)m[sT] }

private Map timer(String message,Map r9,Integer shift=iN2,err=null){ log(message,r9,shift,err,sTIMER)}

@Field static final String sLTH='<'
@Field static final String sGTH='>'
@Field static final String sCLR4D9	= '#2784D9'
@Field static final String sCLRRED	= 'red'
@Field static final String sCLRRED2	= '#cc2d3b'
@Field static final String sCLRGRY	= 'gray'
@Field static final String sCLRGRN	= 'green'
@Field static final String sCLRGRN2	= '#43d843'
@Field static final String sCLRORG	= 'orange'
@Field static final String sLINEBR	= '<br>'
static String span(String str,String clr=sNL,String sz=sNL,Boolean bld=false,Boolean br=false){
	return str ? "<span ${(clr || sz || bld) ? "style='${clr ? "color: ${clr};":sBLK}${sz ? "font-size: ${sz};":sBLK}${bld ? "font-weight: bold;":sBLK}'":sBLK}>${str}</span>${br ? sLINEBR:sBLK}": sBLK
}

@CompileStatic
private Long elapseT(Long t,Long n=wnow()){ return Math.round(d1*n-t) }

@Field static final String sSPCSB7='      │'
@Field static final String sSPCSB6='     │'
@Field static final String sSPCS6 ='      '
@Field static final String sSPCS5 ='     '
@Field static final String sSPCST='┌─ '
@Field static final String sSPCSM='├─ '
@Field static final String sSPCSE='└─ '
@Field static final String sNWL='\n'
@Field static final String sDBNL='\n\n • '

@CompileStatic
static String spanStr(Boolean html,String s){ return html? span(s) : s }

@CompileStatic
static String doLineStrt(Integer level,List<Boolean>newLevel){
	String lineStrt; lineStrt=sNWL
	Boolean dB; dB=false
	Integer i
	for(i=iZ;i<level;i++){
		if(i+i1<level){
			if(!newLevel[i]){
				if(!dB){ lineStrt+=sSPCSB7; dB=true }
				else lineStrt+=sSPCSB6
			}else lineStrt+= !dB ? sSPCS6:sSPCS5
		}else lineStrt+= !dB ? sSPCS6:sSPCS5
	}
	return lineStrt
}

@CompileStatic
static String dumpListDesc(List data,Integer level,List<Boolean> lastLevel,String listLabel,Boolean html=false,Boolean reorder=true){
	String str; str=sBLK
	Integer cnt; cnt=i1
	List<Boolean> newLevel=lastLevel

	List list1=data?.collect{it}
	Integer sz=list1.size()
	for(Object par in list1){
		String lbl=listLabel+"[${cnt-i1}]".toString()
		if(par instanceof Map){
			Map newmap=[:]
			newmap[lbl]=(Map)par
			Boolean t1=cnt==sz
			newLevel[level]=t1
			str+=dumpMapDesc(newmap,level,newLevel,cnt,sz,!t1,html,reorder)
		}else if(par instanceof List || par instanceof ArrayList){
			Map newmap=[:]
			newmap[lbl]=par
			Boolean t1=cnt==sz
			newLevel[level]=t1
			str+=dumpMapDesc(newmap,level,newLevel,cnt,sz,!t1,html,reorder)
		}else{
			String lineStrt
			lineStrt=doLineStrt(level,lastLevel)
			lineStrt+=cnt==i1 && sz>i1 ? sSPCST:(cnt<sz ? sSPCSM:sSPCSE)
			str+=spanStr(html, lineStrt+lbl+": ${par} (${objType(par)})".toString() )
		}
		cnt+=i1
	}
	return str
}

@CompileStatic
static String dumpMapDesc(Map data,Integer level,List<Boolean> lastLevel,Integer listCnt=null,Integer listSz=null,Boolean listCall=false,Boolean html=false,Boolean reorder=true){
	String str; str=sBLK
	Integer cnt; cnt=i1
	Integer sz=data?.size()
	Map svMap,svLMap,newMap; svMap=[:]; svLMap=[:]; newMap=[:]
	for(par in data){
		String k=(String)par.key
		def v=par.value
		if(reorder && v instanceof Map){
			svMap+=[(k): v]
		}else if(reorder && (v instanceof List || v instanceof ArrayList)){
			svLMap+=[(k): v]
		}else newMap+=[(k):v]
	}
	newMap+=svMap+svLMap
	Integer lvlpls=level+i1
	for(par in newMap){
		String lineStrt
		List<Boolean> newLevel=lastLevel
		Boolean thisIsLast=cnt==sz && !listCall
		if(level>iZ)newLevel[(level-i1)]=thisIsLast
		Boolean theLast
		theLast=thisIsLast
		if(level==iZ)lineStrt=sDBNL
		else{
			theLast=theLast && thisIsLast
			lineStrt=doLineStrt(level,newLevel)
			if(listSz && listCnt && listCall)lineStrt+=listCnt==i1 && listSz>i1 ? sSPCST:(listCnt<listSz ? sSPCSM:sSPCSE)
			else lineStrt+=((cnt<sz || listCall) && !thisIsLast) ? sSPCSM:sSPCSE
		}
		String k=(String)par.key
		def v=par.value
		String objType=objType(v)
		if(v instanceof Map){
			str+=spanStr(html, lineStrt+"${k}: (${objType})".toString() )
			newLevel[lvlpls]=theLast
			str+=dumpMapDesc((Map)v,lvlpls,newLevel,null,null,false,html,reorder)
		}
		else if(v instanceof List || v instanceof ArrayList){
			str+=spanStr(html, lineStrt+"${k}: [${objType}]".toString() )
			newLevel[lvlpls]=theLast
			str+=dumpListDesc((List)v,lvlpls,newLevel,sBLK,html,reorder)
		}
		else{
			str+=spanStr(html, lineStrt+"${k}: (${v}) (${objType})".toString() )
		}
		cnt+=i1
	}
	return str
}

@CompileStatic
static String objType(obj){ return span(myObj(obj),sCLRORG) }

@CompileStatic
static String getMapDescStr(Map data,Boolean reorder=true){
	List<Boolean> lastLevel=[true]
	String str=dumpMapDesc(data,iZ,lastLevel,null,null,false,true,reorder)
	return str!=sBLK ? str:'No Data was returned'
}

private static String sectionTitleStr(String title)	{ return '<h3>'+title+'</h3>' }
private static String inputTitleStr(String title)	{ return '<u>'+title+'</u>' }
//private static String pageTitleStr(String title)	{ return '<h1>'+title+'</h1>' }
//private static String paraTitleStr(String title)	{ return '<b>'+title+'</b>' }

@Field static final String sGITP='https://cdn.jsdelivr.net/gh/imnotbob/webCoRE@hubitat-patches/resources/icons/'
private static String gimg(String imgSrc){ return sGITP+imgSrc }

@CompileStatic
private static String imgTitle(String imgSrc,String titleStr,String color=sNL,Integer imgWidth=30,Integer imgHeight=iZ){
	String imgStyle
	imgStyle=sBLK
	String myImgSrc=gimg(imgSrc)
	imgStyle+=imgWidth>iZ ? 'width: '+imgWidth.toString()+'px !important;':sBLK
	imgStyle+=imgHeight>iZ ? imgWidth!=iZ ? sSPC:sBLK+'height:'+imgHeight.toString()+'px !important;':sBLK
	if(color!=sNL) return """<div style="color: ${color}; font-weight:bold;"><img style="${imgStyle}" src="${myImgSrc}"> ${titleStr}</img></div>""".toString()
	else return """<img style="${imgStyle}" src="${myImgSrc}"> ${titleStr}</img>""".toString()
}

static String myObj(obj){
	if(obj instanceof String)return 'String'
	else if(obj instanceof Map)return 'Map'
	else if(obj instanceof List)return 'List'
	else if(obj instanceof ArrayList)return 'ArrayList'
	else if(obj instanceof BigInteger)return 'BigInt'
	else if(obj instanceof Long)return 'Long'
	else if(obj instanceof Integer)return 'Int'
	else if(obj instanceof Boolean)return 'Bool'
	else if(obj instanceof BigDecimal)return 'BigDec'
	else if(obj instanceof Double)return 'Double'
	else if(obj instanceof Float)return 'Float'
	else if(obj instanceof Byte)return 'Byte'
//	else if(obj instanceof com.hubitat.app.DeviceWrapper)return 'Device'
	else return 'unknown'
}


@Field volatile static Map<String,Long> lockTimesVFLD=[:]
@Field volatile static Map<String,String> lockHolderVFLD=[:]

@CompileStatic
void getTheLock(String qname,String meth=sNL,Boolean longWait=false){
	Boolean a=getTheLockW(qname,meth,longWait)
}

@Field static final Long lTHOUS=1000L

@CompileStatic
Boolean getTheLockW(String qname,String meth=sNL,Boolean longWait=false){
	Long waitT=longWait? lTHOUS:10L
	Boolean wait
	wait=false
	Integer semaNum=semaNum(qname)
	String semaSNum=semaNum.toString()
	Semaphore sema=sema(semaNum)
	while(!(sema.tryAcquire())){
		// did not get lock
		Long t
		t=lockTimesVFLD[semaSNum]
		if(t==null){
			t=wnow()
			lockTimesVFLD[semaSNum]=t
			lockTimesVFLD=lockTimesVFLD
		}
		if(isEric())warn "waiting for ${qname} ${semaSNum} lock access, $meth, long: $longWait, holder: ${lockHolderVFLD[semaSNum]}",null
		wpauseExecution(waitT)
		wait=true
		if(elapseT(t)>30000L){
			releaseTheLock(qname)
			if(isEric())warn "overriding lock $meth",null
		}
	}
	lockTimesVFLD[semaSNum]=wnow()
	lockTimesVFLD=lockTimesVFLD
	lockHolderVFLD[semaSNum]=sAppId()+sSPC+meth
	lockHolderVFLD=lockHolderVFLD
	return wait
}

@CompileStatic
static void releaseTheLock(String qname){
	Integer semaNum=semaNum(qname)
	String semaSNum=semaNum.toString()
	Semaphore sema=sema(semaNum)
	lockTimesVFLD[semaSNum]=(Long)null
	lockTimesVFLD=lockTimesVFLD
//	lockHolderVFLD[semaSNum]=sNL
//	lockHolderVFLD=lockHolderVFLD
	sema.release()
}

void clearSema(){
	String pNm=sAppId()
	getTheLock(pNm,'updated')
	theSemaphoresVFLD[pNm]=lZ
	theSemaphoresVFLD=theSemaphoresVFLD
	theQueuesVFLD[pNm]=[]
	theQueuesVFLD=theQueuesVFLD // forces volatile cache flush
	releaseTheLock(pNm)
}

@Field static Semaphore theLock0FLD=new Semaphore(1)

@Field static final Integer iStripes=1
@CompileStatic
static Integer semaNum(String name){
	if(name.isNumber())return name.toInteger()%iStripes
	Integer hash=smear(name.hashCode())
	return Math.abs(hash)%iStripes
}

@CompileStatic
static Semaphore sema(Integer snum){
	switch(snum){
		case 0: return theLock0FLD
		default: //log.error "bad hash result $snum"
			return null
	}
}

private static Integer smear(Integer hashC){
	Integer hashCode
	hashCode=hashC
	hashCode ^= (hashCode >>> i20) ^ (hashCode >>> i12)
	return hashCode ^ (hashCode >>> i7) ^ (hashCode >>> i4)
}


@Field volatile static Map<String,List<Map>> theQueuesVFLD=[:]
@Field volatile static Map<String,Long> theSemaphoresVFLD=[:]

// This can queue event
@CompileStatic
private Map queueSemaphore(Map event){
	Long tt1
	tt1=wnow()
	Long startTime
	startTime=tt1
	Long r_semaphore
	r_semaphore=lZ
	Long semaphoreDelay
	semaphoreDelay=lZ
	String semaphoreName
	semaphoreName=sNL
	Boolean didQ
	didQ=false
	Boolean waited

	String mSmaNm=sAppId()
	waited=getTheLockW(mSmaNm,'queue')
	tt1=wnow()

	Long lastSemaphore
	Boolean clrC
	clrC=false
	Integer qsize
	qsize=iZ
	while(true){
		Long t0=theSemaphoresVFLD[mSmaNm]
		Long tt0=t0!=null ? t0:lZ
		lastSemaphore=tt0
		if(lastSemaphore==lZ || tt1-lastSemaphore>100000L){
			theSemaphoresVFLD[mSmaNm]=tt1
			theSemaphoresVFLD=theSemaphoresVFLD
			semaphoreName=mSmaNm
			semaphoreDelay=waited ? tt1-startTime:lZ
			r_semaphore=tt1
			break
		}

		if(event!=null){
			Map mEvt=event
			List<Map> evtQ
			evtQ=theQueuesVFLD[mSmaNm]
			evtQ=evtQ!=null ? evtQ:(List<Map>)[]
			qsize=evtQ.size()
			if(qsize>i12)clrC=true
			else{
				Boolean a=evtQ.push(mEvt)
				theQueuesVFLD[mSmaNm]=evtQ
				theQueuesVFLD=theQueuesVFLD
				didQ=true
			}
		}
		break
	}
	releaseTheLock(mSmaNm)
	if(clrC){
		error "large queue size ${qsize} clearing",null
		//clear1(true,true,true,true)
	}
	return [
			semaphore:r_semaphore,
			semaphoreName:semaphoreName,
			semaphoreDelay:semaphoreDelay,
			waited:waited,
			exitOut:didQ
	]

}

private wrender(Map options=[:]){ render options }
private Long wnow(){ return (Long)now() }
private Date wtoDateTime(String s){ return (Date)toDateTime(s) }
private String sAppId(){ return ((Long)app.id).toString() }
private void wpauseExecution(Long t){ pauseExecution(t) }

private void wremoveSetting(String s){ app.removeSetting(s) }
void settingUpdate(String name, value, String type=sNL){
	if(name && type){ app?.updateSetting(name, [(sTYPE): type, (sVAL): value]) }
	else if(name && type == sNL){ app?.updateSetting(name, value) }
}
private gtSetting(String nm){ return settings."${nm}" }

private gtSt(String nm){ return state."${nm}" }
private gtAS(String nm){ return atomicState."${nm}" }
/** assign to state  */
private void assignSt(String nm,v){ state."${nm}"=v }
/** assign to atomicState  */
private void assignAS(String nm,v){ atomicState."${nm}"=v }
private Map gtState(){ return state }

private gtLocation(){ return location }


//*******************************************************************
//    CLONE CHILD LOGIC
//*******************************************************************
public Map getSettingsAndStateMap(){
	Map<String,Map> setObjs = [:]
	def vv
	String sk,typ

	((Map)settings).keySet().each{ String theKey->
		sk= theKey
		typ=getSettingType(sk)
		vv= settings[sk]
		if(setObjs[sk]!=null) warn "overwriting ${setObjs[sk]} with ${typ}",null
		if(typ=='time')
			vv= dateTimeFmt(wtoDateTime(vv), "HH:mm")
		if(typ.startsWith('capability')){
			typ= 'capability'
			vv= vv instanceof List ? ((List)vv)?.collect{ it?.id?.toString() } : vv?.id?.toString()
		}
		if(typ=='device')
			vv= vv instanceof List ? ((List)vv)?.collect{ it?.id?.toString() } : vv?.id?.toString()
		setObjs[sk]= [(sTYPE): typ, (sVAL): vv]
	}

	Map data= [:]
	String newlbl= app?.getLabel()?.toString() //?.replace(" (A ${sPAUSESymFLD})", sBLK)
	data.label= newlbl?.replace(" (A)", sBLK)
	List<String> setSkip=['install_device','device_name']
	data.settings= setObjs.findAll{ !(it.key in setSkip) }

	List<String> stateSkip= [
			/* "isInstalled", "isParent", */
			"accessToken", "debugLevel", "endpoint", "endpointSecret", "localEndpointURL", "remoteEndpointURL",
			"dupPendingSetup", "dupOpenedByUser"
	]
	data.state= ((Map<String,Object>)state)?.findAll{ !((String)it?.key in stateSkip) }
	return data
}

private String gtLbl(d){ return "${d?.label ?: d?.name}".toString() }

/**
 *  Color Temp Sync
 *
 *  Copyright 2020 Nektarios
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
definition(
    name: "Color Temp Sync",
    namespace: "nektarios",
    author: "Nektarios",
    description: "Sync smart lights with Color Temperature",
    category: "Lights",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/ModeMagic/rise-and-shine.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/ModeMagic/rise-and-shine@2x.png"
)


preferences {
    page(name: "pageMainPage", content: "pageMainPage", title: "Setup Bulbs", uninstall: true, install: true, nextPage: null)
    
}

def pageMainPage() {
    dynamicPage(name: "pageMainPage") {
        section("Color Temp Bulb") {
           input "colorTempBulb", "capability.colorTemperature", title: "Match color temp of this bult when turned on",  multiple: false, required: true
        }

        section("Logging") {
		    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true	
	    }
        section() {
            input name: "numOfGroups", type:"NUMBER", title: "Number of bulb groups (bulbs in each groups will be processed simultaneously)",description: "1-20", range: "1..20", required: true, submitOnChange: true, width:4
        }
        log.debug "numOfGroups = $numOfGroups"
        if (numOfGroups >= 1) {
            section(){
                (1..numOfGroups).each() { n ->
                    section("Bulb Group ${n}") {
                        input "group${n}", "capability.colorTemperature", title:"Select color temp bulbs", multiple:true, required:false
                        input "groupDelayCount${n}", "NUMBER", title:"Group Delay Count (after this many bulbs, a delay will be inserted)", defaultValue:4, required:true
                        input "groupDelay${n}", "NUMBER", title:"Group Delay (ms)", defaultValue:0, required:true
                        input "groupSetOnlyIfOn${n}", "bool", title:"Set the color temp only if on", defaultValue:false, required:false
                        input "groupColorTempOffset${n}", "NUMBER", title:"+/- Offset of color temperature", defaultValue:0, required:false
                        input "groupScenes${n}", "capability.switch", title:"If any of these scenes are on, do not adjust the temperature when turning the bulbs on.", multiple:true, required:false
                    }
                    
                }
            }
        }
        
        section() {
            input name: "betweenGroupDelay", type:"NUMBER", title: "Delay between groups (ms)", defaultValue: 1000, required: true
        }
    }
}
    
def installed() {
	log.debug "Installed with settings: ${settings}"
	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"
    state.clear()
	unsubscribe()
	initialize()
}
    
def log(message) {
    if (logEnable) { log.debug message }
}

def initialize() {
    log.warn "debug logging is: ${logEnable == true}"
	if (logEnable) runIn(1800,logsOff)
    
    state.clear

    subscribeToEvents()
}



def subscribeToEvents() {
    (1..numOfGroups).each() { n ->
        subscribe(getGroup(n), "switch.on", bulbOnHandler)
    }
    subscribe(colorTempBulb, "colorTemperature", colorTempChangedHandler)
    
}

def colorTempChangedHandler(evt) {
    //color temp changed, update all devices
    syncBulbColors(false)
    //6/1 temporarily disable
    def params = [:]
    params.attempt = 1
    params.onlyGroupNum = null
    runInMillis(5000, "correctDiscrepancies", [data: params])
}

def correctDiscrepancies(params) {
    def attempt = params.attempt
    def onlyGroupNum = params.onlyGroupNum
    //log ("OGN $onlyGroupNum")
    
   switch(attempt) { 
       case 1: 
           if (syncBulbColors(false, onlyGroupNum)) {
               log("found some bulbs out of sync attempt: $attempt")
               params.attempt = params.attempt+1
               runInMillis(5000, "correctDiscrepancies", [data:params])
           }
           else {
               log("all bulbs OK")
           }
           break
       case 2:
           if (syncBulbColors(false, onlyGroupNum)) {
               log("found some bulbs out of sync attempt: $attempt")
               params.attempt = params.attempt+1
               runInMillis(8000, "correctDiscrepancies", [data:params])
           }
           else {
               log("all bulbs OK")
           }
           break
       case 3: 
           if (syncBulbColors(false, onlyGroupNum)) {
               log("found some bulbs out of sync attempt: $attempt")
               params.attempt = params.attempt+1
               runInMillis(20000, "correctDiscrepancies", [data:params])
           } 
           else {
               log("all bulbs OK")
           }
           break
    }

    //try to sync changes up to 3 times incase some aren't changing.
    /*if (syncBulbColors(false)) {
        pauseExecution(8000)
        if (syncBulbColors(false)) {
            pauseExecution(20000)
            syncBulbColors(false)
        }
    }*/
}


def syncBulbColors(forceChange=false, onlyGroupNum=null) {
    def anyChanges = false
    //log ("OGN2 $onlyGroupNum")
    
    if (forceChange) {
        anyChanges = true //needed for below - the delay needs to be enforced if we are forcing all changes.
    }
    
    def correctCT = colorTempBulb.latestValue("colorTemperature").toInteger()
    
    //update CT of bulbs in each device group
    //(1..numOfGroups).each() { n ->
    def start=1
    def stop=numOfGroups
    
    if (onlyGroupNum != null) {
        log("only checking $onlyGroupNum")
        start=onlyGroupNum
        stop=onlyGroupNum
    }
    
    for (int n=start; n<=stop; n++) {
        def group = getGroup(n)
        def groupDelay = getGroupDelay(n)
        def groupDelayCount = getGroupDelayCount(n)
        def groupColorTempOffset = getGroupColorTempOffset(n)
        
        //for each device in the group, update it
        def devCount = 0
        def anyChangesInGroup = false
        group.each() { dev -> 
            
            if (forceChange) {
                dev.setColorTemperature(correctCT)
                anyChangesInGroup = true
            }
            else {
                def deviceCT = dev.latestValue("colorTemperature").toInteger()
                if ( Math.abs(deviceCT - correctCT) >= 25) {
                    //if device supports color pre-staging then set it, otherwise only set if the device is ON
                    if (!getGroupSetOnlyIfOn(n) || dev.latestValue("switch") == "on") {
                        dev.setColorTemperature(correctCT+groupColorTempOffset)
                        anyChanges = true
                        anyChangesInGroup = true
                        log("$dev was out of sync $deviceCT != $correctCT)")
                    }
                }
            }
            
            if (devCount % groupDelayCount == 0 && anyChangesInGroup) {
                //add delay every N devices, but only if any changes have been in this group so far
                pauseExecution(groupDelay)
            }
            devCount++
        }
        
        //delay after each group, if there are more
        if (n < numOfGroups && anyChanges) {
            //add a delay, but only if changes have been made already
            pauseExecution(betweenGroupDelay)
        }
    }
    
    return (anyChanges || forceChange)
}

/*def refreshDelay(devId, command, delayMS) {
    runInMillis(delayMS, commander, [data: [dev: devId, cmd: command]])
}

def commander(data) {
    //def dev = controlSwitches.find {it.deviceId == data.dev} // NEED TO UPDATE IF SUPPORTING MULTIPLE SWITCHES
    def dev =  controlSwitch
    def cmd = data.cmd
    dev."$cmd"()
}*/

def getDeviceGroup(findDeviceId) {
    for (def n=1; n<=numOfGroups; n++) {
        def group = getGroup(n)
        
        //for each device in the group, check if we can find the device ID
        def dev = group.find() { dev -> dev.deviceId == findDeviceId}
        if (dev) {
            return n
        }
    }
    return 0
}

def getDeviceOffset(findDeviceId) {
    def n=getDeviceGroup(findDeviceId)
    if (n>0) {
        return getGroupColorTempOffset(n)
    }
    else {
        return 0
    }
    
    /*def groupColorTempOffset = -1 //getGroupColorTempOffset(n)

    for (def n=1; n<numOfGroups; n++) {
        def group = getGroup(n)
        
        //for each device in the group, update it
        def devCount = 0
        def anyChangesInGroup = false
        def dev = group.find() { dev -> dev.deviceId == findDeviceId}
        if (dev) {
            return getGroupColorTempOffset(n)
        }
    }
    return 0*/
}


def bulbOnHandler(evt) {
    if (evt.name == "switch" && evt.value == "on") {
        if (!evt.device) {
            log (evt)
            log (evt.name)
            return
        }
        def dev = evt.device
        def devId = dev.deviceId
        def deviceCT = dev.latestValue("colorTemperature")
        def correctCT = colorTempBulb.latestValue("colorTemperature").toInteger() + getDeviceOffset(devId)
        
        //log("****** $dev ct ${dev.latestValue("colorTemperature")} correctCT $correctCT")
        
        //if color of group should be set when on, else if the color temp is incorrect, then set the color temp
        def groupNum = getDeviceGroup(devId)
        //log("grP: $groupNum")
        if (!isAnyGroupSceneOn(groupNum) && (getGroupSetOnlyIfOn(groupNum) || deviceCT==null || deviceCT.toInteger() != correctCT)) {
            def params = [:]
            params.attempt = 1
            params.onlyGroupNum = groupNum
            runInMillis(2000, "correctDiscrepancies", [data: params])

            def grp = getGroup(groupNum)
            //for some reason dev can become null so get the group
            //log("device $dev groupNum $groupNum group $grp")
            if (grp) {
                def newDev = grp.findAll{it.id == devId}
                newDev*.setColorTemperature(correctCT)
            }
            else if (dev) {
                //extra check in here.. for some reason dev can become null...
                dev.setColorTemperature(correctCT)
            }
            if (dev.hasCommand("refresh")) {
                dev.refresh()
            }
        }
    }
}


def isAnyGroupSceneOn(num) {
    def groupScenes = getGroupScenes(num)
    if (groupScenes) {
        def scenesOn = groupScenes.findAll() {d -> d.latestValue("switch")=="on"}
        if (scenesOn && scenesOn.size() > 0) {
            log("A scene is ON! $scenesOn")
            return true
        }
    }
    return false
}

def getGroup(num){
   return settings."group$num"
   /*switch(num) { 
       case 1: return group1
       case 2: return group2
       case 3: return group3
       case 4: return group4
       case 5: return group5
       case 6: return group6
       case 7: return group7
       case 8: return group8
       case 9: return group9
       case 10: return group10
       case 11: return group11
       case 12: return group12
       case 13: return group13
       case 14: return group14
       case 15: return group15
       case 16: return group16
       case 17: return group17
       case 18: return group18
       case 19: return group19
       case 20: return group20
    }
    return null
   */
}

def getGroupDelay(num){
    return settings."groupDelay$num"
 /*
    switch(num) { 
       case 1: return groupDelay1
       case 2: return groupDelay2
       case 3: return groupDelay3
       case 4: return groupDelay4
       case 5: return groupDelay5
       case 6: return groupDelay6
       case 7: return groupDelay7
       case 8: return groupDelay8
       case 9: return groupDelay9
       case 10: return groupDelay10
       case 11: return groupDelay11
       case 12: return groupDelay12
       case 13: return groupDelay13
       case 14: return groupDelay14
       case 15: return groupDelay15
       case 16: return groupDelay16
       case 17: return groupDelay17
       case 18: return groupDelay18
       case 19: return groupDelay19
       case 20: return groupDelay20
    }
    return null*/
}

def getGroupDelayCount(num){
    return settings."groupDelayCount$num"
   /*switch(num) { 
 
       case 1: return groupDelayCount1
       case 2: return groupDelayCount2
       case 3: return groupDelayCount3
       case 4: return groupDelayCount4
       case 5: return groupDelayCount5
       case 6: return groupDelayCount6
       case 7: return groupDelayCount7
       case 8: return groupDelayCount8
       case 9: return groupDelayCount9
       case 10: return groupDelayCount10
       case 11: return groupDelayCount11
       case 12: return groupDelayCount12
       case 13: return groupDelayCount13
       case 14: return groupDelayCount14
       case 15: return groupDelayCount15
       case 16: return groupDelayCount16
       case 17: return groupDelayCount17
       case 18: return groupDelayCount18
       case 19: return groupDelayCount19
       case 20: return groupDelayCount20
    }
    return null*/
}

def getGroupSetOnlyIfOn(num){
       return settings."groupSetOnlyIfOn$num"
}

def getGroupColorTempOffset(num){
        def offset = settings."groupColorTempOffset$num"
        if (!offset) return 0
        return offset
}

def getGroupScenes(num){
        return settings."groupScenes$num"
}



def logsOff(){
	log.warn "debug logging disabled..."
	logEnable = false
}

  
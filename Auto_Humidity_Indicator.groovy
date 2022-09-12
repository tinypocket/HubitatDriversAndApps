/**
 *  Auto Humidity Indicator
 *
 *  Copyright 2022 Nektarios
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
 
 
definition (

    name: "Auto Humidity Indicator",
    namespace: "nektarios",
    author: "Nektarios",
    description: "Reflect the current and target humidity levl in a WD200/300 dimmer LEDS",
    category: "Convenience",
    iconUrl: "https://s3.amazonaws.com/smartthings-device-icons/Appliances/appliances11-icn.png",
    iconX2Url: "https://s3.amazonaws.com/smartthings-device-icons/Appliances/appliances11-icn@2x.png"

)


preferences {

    section("Dimmers that reflect target/current humidity") {
        input "startHumidityLevel", "capability.switchLevel", title:"Target Level Dimmer", multiple:false, required:true
        input "currentHumidityLevel", "capability.switchLevel", title:"Current Level Dimmer", multiple:false, required:true
    }
    section("WD200/300+ Dimmer with LEDs to indicate humidity") {
        input "wdSwitch", "capability.switch", title: "WD200 switch to set the state of",  multiple: true, required: false
    }
    section("Logging") {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true    
    }
}


def installed() {
    log.debug "${app.label} installed with settings: ${settings}"
    initialize()

}

def uninstalled()
{
    send("${app.label} uninstalled.")   
    state.appEnabled = false
}


def updated() {
    log.debug "${app.label} updated with settings: ${settings}"
    unsubscribe()
    initialize()
}



def initialize() {
    log.warn "debug logging is: ${logEnable == true}"
    if (logEnable) runIn(1800,logsOff)
    
    state.clear()
    ledPercentHumidity = [:]
    ledStatus = [:]
    
    subscribe(startHumidityLevel, "level", levelChangeEvent) //, [filterEvents: false]
    subscribe(currentHumidityLevel, "level", levelChangeEvent) //, [filterEvents: false]
    updateLedHumidity()
}

def levelChangeEvent(evt) {
    if(evt) {
        log("levelChangeEvent() ${evt.descriptionText}")
    }
    updateLedHumidity()
}

def updateLedHumidity() {
    def startLevel = startHumidityLevel.latestValue("level")
    def currentLevel = currentHumidityLevel.latestValue("level")

    log("updateLedHumidity: current: $currentLevel start:$startLevel")
    if (startLevel == currentLevel) {
        wdSwitch.setSwitchModeNormal()
        return
    }
    wdSwitch.setSwitchModeStatus()

    //Array holding the humidity above which the LED should be lit
    state.ledPercentHumidity = [:]
    state.ledPercentHumidity[7] = 89
    state.ledPercentHumidity[1] = 50

    ledPercentPerLED = (state.ledPercentHumidity[7] - state.ledPercentHumidity[1]) / 6

    //determine buckets
    for (def i=2; i<=7; i++) {
        state.ledPercentHumidity[i] = state.ledPercentHumidity[i-1] +  ledPercentPerLED
    }
    
    state.ledStatus = [:]
    //color LEDs
    //color reference 0=Off, 1=Red, 2=Green, 3=Blue, 4=Magenta, 5=Yellow, 6=Cyan, 7=White
    for (def i=1; i<=7; i++) {
        //indicate start level with green
        if (i<7 && startLevel >= state.ledPercentHumidity[i] && startLevel < state.ledPercentHumidity[i+1] ) {
            state.ledStatus[i] = 2 //green
        }
        //indicate current level and below with blue
        else if (currentLevel >= state.ledPercentHumidity[i]) {
            state.ledStatus[i] = 3 //blue
        }
        else {
            state.ledStatus[i] = 0 //white
        }
    }
    refreshLeds(false)

}


def refreshLed(ledNum) {
    refreshLed(ledNum, "set")
}

def refreshLed(ledNum, getValue) {
    def String ledColor = "0"
    def String blink = "0"
    switch (ledNum) {
        case 7:
            ledColor = state.ledStatus[7]
            blink = "0"
            break
        case 6:
            ledColor = state.ledStatus[6]
            blink = "0"
            break
        case 5:
            ledColor = state.ledStatus[5]
            blink = "0"
            break
        case 4:
            ledColor = state.ledStatus[4]
            blink = "0"
            break
        case 3:
            ledColor = state.ledStatus[3]
            blink = "0"
            break
        case 2:
            ledColor =state.ledStatus[2]
            blink = "0"
            break
        case 1:
            ledColor = state.ledStatus[1]
            blink = "0"
            break
    }
    if (getValue  =="set") {
        log("updating $ledNum to $ledColor")
        wdSwitch*.setStatusLed(ledNum.toInteger(),ledColor.toInteger(),blink.toInteger())
        return ledColor
    }
    else if (getValue == "color") {
        return ledColor
    }
    else if (getValue == "blink") {
        return blink
    }
}

def refreshLeds(Boolean force)
{
    def String led7 = refreshLed(7, "color")
    def String led6 = refreshLed(6, "color")
    def String led5 = refreshLed(5, "color")
    def String led4 = refreshLed(4, "color")
    def String led3 = refreshLed(3, "color")
    def String led2 = refreshLed(2, "color")
    def String led1 = refreshLed(1, "color")
    
    def String ledBlink7 = "0"
    def String ledBlink6 = "0"
    def String ledBlink5 = "0"
    def String ledBlink4 = "0"
    def String ledBlink3 = "0"
    def String ledBlink2 = "0"
    def String ledBlink1 = "0"
    
    log(led1+led2+led3+led4+led5+led6+led7 + " " + force)
    
    def forceVal = 0
    if (force) forceVal = 1
    
    wdSwitch*.setAllStatusLed(led1+led2+led3+led4+led5+led6+led7,   ledBlink1+ledBlink2+ledBlink3+ledBlink4+ledBlink5+ledBlink6+ledBlink7,   forceVal)
}

def log(message) {
    if (logEnable) { log.debug message }
}
def logsOff(){
    log.warn "debug logging disabled..."
    logEnable = false
}
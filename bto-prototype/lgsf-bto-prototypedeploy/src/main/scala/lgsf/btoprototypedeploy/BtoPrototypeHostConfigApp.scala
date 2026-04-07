package lgsf.btoprototypedeploy

import csw.framework.deploy.hostconfig.HostConfig
import csw.prefix.models.Subsystem

object BtoPrototypeHostConfigApp {

  def main(args: Array[String]): Unit = {
    HostConfig.start("bto-prototype_host_config_app", Subsystem.withNameInsensitive("LGSF"), args)
  }
}

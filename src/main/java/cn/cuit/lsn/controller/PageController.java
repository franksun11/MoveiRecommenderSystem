package cn.cuit.lsn.controller;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;


@Controller
public class PageController {
    private static Logger logger = LoggerFactory.getLogger(PageController.class);

	@Autowired
    //注入Service

	
	@RequestMapping("/index")
	public String index(){
        logger.info("----------------------------------------------"  + "I'm LOG");
		return "index";
	}

    @RequestMapping("/upload")
    public String upload(){

        return "upload";
    }

    @RequestMapping("/loginPage")
	public String login(){

		return "login";
	}

	@RequestMapping("/registerPage")
	public String register(){

		return "register";
	}

	@RequestMapping("/algorithm")
	public String algorithmSetting(){


		return "algorithm";
	}

	@RequestMapping("/showRecommendation")
	public String showOff(){

		return "show";
	}
}

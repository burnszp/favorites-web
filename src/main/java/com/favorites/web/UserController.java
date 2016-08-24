package com.favorites.web;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import javax.annotation.Resource;
import javax.mail.internet.MimeMessage;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.favorites.comm.Const;
import com.favorites.domain.Collect;
import com.favorites.domain.Config;
import com.favorites.domain.ConfigRepository;
import com.favorites.domain.Favorites;
import com.favorites.domain.FavoritesRepository;
import com.favorites.domain.FollowRepository;
import com.favorites.domain.User;
import com.favorites.domain.UserRepository;
import com.favorites.domain.result.ExceptionMsg;
import com.favorites.domain.result.Response;
import com.favorites.domain.result.ResponseData;
import com.favorites.service.CollectService;
import com.favorites.service.ConfigService;
import com.favorites.service.FavoritesService;
import com.favorites.utils.DateUtils;
import com.favorites.utils.MD5Util;
import com.favorites.utils.MessageUtil;

@RestController
@RequestMapping("/user")
public class UserController extends BaseController {
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private FavoritesRepository favoritesRepository;
	@Resource
	private ConfigService configService;
	@Resource
	private FavoritesService favoritesService;
	@Resource
	private CollectService collectService;
	@Resource
    private JavaMailSender mailSender;
	@Value("${spring.mail.username}")
	private String mailFrom;
	@Value("${mail.subject.forgotpassword}")
	private String mailSubject;
	@Value("${mail.content.forgotpassword}")
	private String mailContent;
	@Autowired	
	private ConfigRepository configRepository;
	@Autowired
	private FollowRepository followRepository;
	
	
	@RequestMapping(value = "/login", method = RequestMethod.POST)
	public ResponseData login(User user) {
		logger.info("login begin, param is " + user);
		try {
			User loginUser = userRepository.findByUserNameOrEmail(user.getUserName(), user.getUserName());
			if (loginUser == null || !loginUser.getPassWord().equals(getPwd(user.getPassWord()))) {
				return new ResponseData(ExceptionMsg.LoginNameOrPassWordError);
			}
			getSession().setAttribute(Const.LOGIN_SESSION_KEY, loginUser);
			String preUrl = "";
			if(null != getSession().getAttribute(Const.LAST_REFERER)){
				preUrl = String.valueOf(getSession().getAttribute(Const.LAST_REFERER));
				if(preUrl.indexOf("/collect?") < 0){
					preUrl = "/";
				}
			}
			return new ResponseData(ExceptionMsg.SUCCESS, preUrl);
		} catch (Exception e) {
			// TODO: handle exception
			logger.error("login failed, ", e);
			return new ResponseData(ExceptionMsg.FAILED);
		}
	}

	@RequestMapping(value = "/regist", method = RequestMethod.POST)
	public Response create(User user) {
		logger.info("create user begin, param is " + user);
		try {
			User registUser = userRepository.findByEmail(user.getEmail());
			if (null != registUser) {
				return result(ExceptionMsg.EmailUsed);
			}
			User userNameUser = userRepository.findByUserName(user.getUserName());
			if (null != userNameUser) {
				return result(ExceptionMsg.UserNameUsed);
			}
			user.setPassWord(getPwd(user.getPassWord()));
			user.setCreateTime(DateUtils.getCurrentTime());
			user.setLastModifyTime(DateUtils.getCurrentTime());
			userRepository.save(user);
			// 添加默认收藏夹
			Favorites favorites = favoritesService.saveFavorites(user.getId(),0l, "未读列表");
			// 添加默认属性设置
			configService.saveConfig(user.getId(),String.valueOf(favorites.getId()));
			getSession().setAttribute(Const.LOGIN_SESSION_KEY, user);
		} catch (Exception e) {
			// TODO: handle exception
			logger.error("create user failed, ", e);
			return result(ExceptionMsg.FAILED);
		}
		return result();
	}

	@RequestMapping(value = "/collect", method = RequestMethod.POST)
	public Response collect(Collect collect) {
		logger.info("collect begin, param is " + collect);
		try {
			collect.setUserId(getUserId());
			if(collectService.checkCollect(collect)){
				if(collect.getId()==null){
					collectService.saveCollect(collect);
				}else{
					collectService.updateCollect(collect);
				}
			}else{
				return result(ExceptionMsg.CollectExist);
			}
		} catch (Exception e) {
			// TODO: handle exception
			logger.error("collect failed, ", e);
			return result(ExceptionMsg.FAILED);
		}
		return result();
	}

	@RequestMapping(value = "/getFavorites", method = RequestMethod.POST)
	public List<Favorites> getFavorites() {
		logger.info("getFavorites begin");
		List<Favorites> favorites = null;
		try {
			favorites = favoritesRepository.findByUserId(getUserId());
		} catch (Exception e) {
			// TODO: handle exception
			logger.error("getFavorites failed, ", e);
		}
		logger.info("getFavorites end favorites ==" + favorites);
		return favorites;
	}
	
	/**
	 * 获取属性设置
	 * @return
	 */
	@RequestMapping(value = "/getConfig", method = RequestMethod.POST)
	public Config getConfig(){
		Config config = new Config();
		try {
			config = configRepository.findByUserId(getUserId());
		} catch (Exception e) {
			logger.error("异常：",e);
		}
		return config;
	}
	
	/**
	 * 属性修改
	 * @param id
	 * @param type
	 * @return
	 */
	@RequestMapping(value = "/updateConfig", method = RequestMethod.POST)
	public Response updateConfig(Long id, String type,String defaultFavorites){
		logger.info("param,id:" + id + "----type:" + type + "-----defaultFavorites:" + defaultFavorites);
		if(null  != id && StringUtils.isNotBlank(type)){
			try {
				configService.updateConfig(id, type,defaultFavorites);
			} catch (Exception e) {
				logger.error("属性修改异常：",e);
			}
		}
		return result();
	}
	
	@RequestMapping(value="/getFollows")
	public List<String> getFollows() {
		List<String> followList = followRepository.findByUserId(getUserId());
		return followList;
	}
	
	@RequestMapping(value = "/sendForgotPasswordEmail", method = RequestMethod.POST)
	public Response sendForgotPasswordEmail(String email) {
		logger.info("sendForgotPasswordEmail begin, param is " + email);
		try {
			User registUser = userRepository.findByEmail(email);
			if (null == registUser) {
				return result(ExceptionMsg.EmailNotRegister);
			}	
			String secretKey = UUID.randomUUID().toString(); // 密钥
            Timestamp outDate = new Timestamp(System.currentTimeMillis() + 30 * 60 * 1000);// 30分钟后过期
            long date = outDate.getTime() / 1000 * 1000;
            userRepository.setOutDateAndValidataCode(outDate+"", secretKey, email);
            String key =email + "$" + date + "$" + secretKey;
            System.out.println(" key>>>"+key);
            String digitalSignature = MD5Util.encrypt(key);// 数字签名
            String path = this.getRequest().getContextPath();
            String basePath = this.getRequest().getScheme() + "://"
                    + this.getRequest().getServerName() + ":"
                    + this.getRequest().getServerPort() + path + "/";
            String resetPassHref = basePath + "newPassword?sid="
                    + digitalSignature +"&email="+email;
            String emailContent = MessageUtil.getMessage(mailContent, resetPassHref);					
	        MimeMessage mimeMessage = mailSender.createMimeMessage();	        
	        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);
	        helper.setFrom(mailFrom);
	        helper.setTo(email);
	        helper.setSubject(mailSubject);
	        helper.setText(emailContent, true);
	        mailSender.send(mimeMessage);
		} catch (Exception e) {
			// TODO: handle exception
			logger.error("sendForgotPasswordEmail failed, ", e);
			return result(ExceptionMsg.FAILED);
		}
		return result();
	}
	
	@RequestMapping(value = "/setNewPassword", method = RequestMethod.POST)
	public Response setNewPassword(String newpwd, String email, String sid) {
		logger.info("setNewPassword begin, param is " + email);
		try {
			User user = userRepository.findByEmail(email);
			Timestamp outDate = Timestamp.valueOf(user.getOutDate());
			if(outDate.getTime() <= System.currentTimeMillis()){ //表示已经过期
				return result(ExceptionMsg.LinkOutdated);
            }
            String key = user.getEmail()+"$"+outDate.getTime()/1000*1000+"$"+user.getValidataCode();//数字签名
            String digitalSignature = MD5Util.encrypt(key);
            if(!digitalSignature.equals(sid)) {
            	 return result(ExceptionMsg.LinkOutdated);
            }
            userRepository.setNewPassword(getPwd(newpwd), email);
		} catch (Exception e) {
			// TODO: handle exception
			logger.error("setNewPassword failed, ", e);
			return result(ExceptionMsg.FAILED);
		}
		return result();
	}

}
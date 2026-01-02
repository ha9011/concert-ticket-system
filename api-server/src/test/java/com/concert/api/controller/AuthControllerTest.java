package com.concert.api.controller;

import com.concert.api.service.AuthService;
import com.concert.common.dto.SignupRequest;
import com.concert.common.entity.User;
import com.concert.common.exception.CustomException;
import com.concert.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [통합 테스트 학습 가이드]
 * 
 * @SpringBootTest
 * - 스프링 컨테이너를 실제로 띄워서 테스트합니다. (모든 빈을 로드함)
 * - 장점: 실제 운영 환경과 가장 비슷합니다.
 * - 단점: 무겁고 느립니다. (단위 테스트 @WebMvcTest보다)
 *
 * @AutoConfigureMockMvc
 * - MockMvc(가짜 브라우저)를 자동으로 설정해서 주입해 줍니다.
 * - 서버를 띄우지 않고도 HTTP 요청/응답 테스트를 가능하게 합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    /**
     * [MockMvc]
     * - 역할: 컨트롤러에게 요청을 보내는 '가짜 사용자(브라우저)' 역할입니다.
     * - 사용법: perform(post("/url")) 처럼 요청을 만들어서 보냅니다.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * [ObjectMapper]
     * - 역할: 자바 객체(Java Object) <-> JSON 문자열 변환기입니다.
     * - API 요청을 보낼 때 객체를 JSON으로 만들어서(직렬화) 보내야 하므로 필요합니다.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * [MockBean]
     * - 역할: 실제 Bean 대신 끼워 넣는 '가짜 객체(Mock)'입니다.
     * - 왜 쓰는가?: 컨트롤러 테스트인데 AuthService(로직/DB)까지 진짜를 쓰면 테스트가 복잡해집니다.
     *   그래서 "서비스는 무조건 성공한다고 가정하자!"라고 가짜를 세워두는 겁니다.
     *
     */
    @MockBean
    private AuthService authService;

    @Test
    @DisplayName("회원가입 API 성공 테스트 - 인증번호가 맞으면 성공해야 함")
    void verifyAndSignup_Success() throws Exception {
        // [1] Given: 테스트 준비 단계
        SignupRequest request = new SignupRequest();
        request.setEmail("test@example.com");
        request.setCode("1234");
        request.setName("테스터");
        request.setPassword("password123");

        User mockUser = User.builder().id(99L).email("test@example.com").build();

        // =================================================================================
        // [Stubbing (스터빙) 이란?]
        // - Mock 객체(가짜)에게 "누가 너한테 이런 요청을 하면, 넌 무조건 이렇게 대답해!"라고
        //   행동 지침을 미리 정해두는 행위입니다.
        // - given(메서드호출).willReturn(리턴값) 형태를 가집니다.
        // =================================================================================
        
        // 상황: authService.verifyAndSignup()이 호출되면 (인자가 무엇이든 간에: any())
        // 결과: 무조건 위에서 만든 mockUser(id=99)를 리턴해라! 라고 연기를 시키는 겁니다.
        given(authService.verifyAndSignup(any(SignupRequest.class))).willReturn(mockUser);

        // [2] When & Then: 실행 및 검증 단계
        mockMvc.perform(post("/api/auth/verify") // 1. POST 요청 생성
                        .contentType(MediaType.APPLICATION_JSON) // 2. "나 JSON 보낸다" 헤더 설정
                        .content(objectMapper.writeValueAsString(request))) // 3. 진짜 JSON 데이터 본문에 넣기
                .andDo(print()) // 4. 콘솔에 요청/응답 로그 찍기 (디버깅용)
                .andExpect(status().isOk()) // 5. HTTP 상태 코드가 200(OK) 맞니?
                .andExpect(jsonPath("$.success").value(true)) // 6. JSON 응답의 success 필드가 true니?
                .andExpect(jsonPath("$.data").value(99L)); // 7. 리턴된 ID가 아까 Stubbing한 99번 맞니?
    }

    @Test
    @DisplayName("회원가입 실패 테스트 - 잘못된 인증번호 입력 시 400 에러")
    void verifyAndSignup_Fail_InvalidCode() throws Exception {
        // [Given]
        SignupRequest request = new SignupRequest();
        request.setEmail("wrong@example.com");
        request.setCode("0000");

        // [Stubbing: 예외 발생 시나리오]
        // 상황: 서비스 메서드가 호출되면
        // 결과: "인증번호 틀림(INVALID_AUTH_CODE)" 에러를 강제로 던져라! (.willThrow)
        given(authService.verifyAndSignup(any(SignupRequest.class)))
                .willThrow(new CustomException(ErrorCode.INVALID_AUTH_CODE));

        // [When & Then]
        mockMvc.perform(post("/api/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isBadRequest()) // 예상대로 400 에러가 났는지 확인
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_AUTH_CODE.getMessage())); // 에러 메시지가 "인증번호가 틀렸거나..." 인지 확인
    }


    @Test
    @DisplayName("인증번호 발송 성공 테스트")
    void sendCode_success() throws Exception {
        // [Given]
        String email = "new-user@example.com";

        // [Stubbing]
        // 상황: sendVerificationCode("new-user@example.com") 가 호출되면
        // 결과: "1234" 코드 결과값을 줘라
        given(authService.sendVerificationCode(email))
                .willReturn("1234");



        // [When & Then]
        mockMvc.perform(post("/api/auth/send-code")
                        .param("email", email)) // Query Parameter (?email=...) 방식 테스트
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("1234"));
    }

    @Test
    @DisplayName("인증번호 발송 실패 테스트 - 이미 가입된 이메일인 경우")
    void sendCode_Fail_DuplicateEmail() throws Exception {
        // [Given]
        String email = "already@example.com";

        // [Stubbing]
        // 상황: sendVerificationCode("already@example.com") 가 호출되면
        // 결과: "중복 이메일(DUPLICATE_EMAIL)" 에러를 던져라!
        given(authService.sendVerificationCode(email))
                .willThrow(new CustomException(ErrorCode.DUPLICATE_EMAIL));

        // [When & Then]
        mockMvc.perform(post("/api/auth/send-code")
                        .param("email", email)) // Query Parameter (?email=...) 방식 테스트
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.DUPLICATE_EMAIL.getMessage()));
    }
}

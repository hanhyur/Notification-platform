package me.hanhyur.notify.api.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ConfigurationProperties("notification.auth")
record OperatorAccounts(Map<String, Account> operators,
                       @DefaultValue("30") int loginRequestsPerMinute,
                       @DefaultValue("120") int registrationRequestsPerMinute) implements UserDetailsService {

    OperatorAccounts {
        operators = operators == null ? Map.of() : Map.copyOf(operators);
        var ids = new HashSet<UUID>();
        operators.forEach((username, account) -> {
            if (username.isBlank() || username.codePointCount(0, username.length()) > 100
                    || !ids.add(account.operatorId())) {
                throw new IllegalArgumentException("운영자 계정의 이름과 고유 번호를 확인하세요.");
            }
        });
        if (loginRequestsPerMinute < 1 || registrationRequestsPerMinute < 1) {
            throw new IllegalArgumentException("관리자 API의 분당 요청 한도는 양수여야 합니다.");
        }
    }

    record Account(UUID operatorId, String displayName, String passwordHash) {
        Account {
            if (operatorId == null || displayName == null || displayName.isBlank()
                    || displayName.codePointCount(0, displayName.length()) > 100
                    || passwordHash == null || !passwordHash.matches("[a-fA-F0-9]{96}")) {
                throw new IllegalArgumentException("운영자 번호, 표시 이름, PBKDF2 비밀번호 해시를 확인하세요.");
            }
        }

        @Override
        public String toString() {
            return "Account[operatorId=" + operatorId + "]";
        }
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        var account = operators.get(username);
        if (account == null) {
            throw new UsernameNotFoundException("등록된 계정을 찾을 수 없습니다.");
        }
        return User.withUsername(username).password(account.passwordHash()).roles("OPERATOR").build();
    }

    OperatorSession session(String authenticatedUsername) {
        var account = operators.get(authenticatedUsername);
        return new OperatorSession(account.operatorId(), account.displayName(), List.of("OPERATOR"));
    }
}

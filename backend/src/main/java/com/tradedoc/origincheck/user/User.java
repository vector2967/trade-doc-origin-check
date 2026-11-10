package com.tradedoc.origincheck.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 100)
    private String company;

    /** 체험 사용자(계정 없이 써 보기). 로그인할 수 없고 expires_at 뒤에 지워진다 */
    @Column(nullable = false)
    private boolean demo;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
    }

    public User(String email, String passwordHash, String name, String company) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.company = company;
    }

    /** 체험 사용자. 비밀번호 해시는 아무도 모르는 값이고, 로그인은 {@code demo} 로 따로 막는다 */
    public static User demo(String email, String unusablePasswordHash, Instant expiresAt) {
        User u = new User(email, unusablePasswordHash, "체험 사용자", null);
        u.demo = true;
        u.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
        return u;
    }

    @PrePersist
    void prePersist() {
        createdAt = now();
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public String getCompany() {
        return company;
    }

    public boolean isDemo() {
        return demo;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** DB(DATETIME(6))에 저장되는 정밀도(마이크로초)에 맞춘 지금 시각. 리눅스 JVM 은 나노초까지 주므로 응답 값과 다시 읽은 값이 달라진다 */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}

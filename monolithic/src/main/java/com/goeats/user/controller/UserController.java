package com.goeats.user.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.user.entity.User;
import com.goeats.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/{id}")
    public ApiResponse<User> getUser(@PathVariable Long id) {
        return ApiResponse.ok(userService.getUser(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<User> createUser(@RequestParam String email,
                                        @RequestParam String name,
                                        @RequestParam String phone,
                                        @RequestParam String address) {
        return ApiResponse.ok(userService.createUser(email, name, phone, address));
    }
}

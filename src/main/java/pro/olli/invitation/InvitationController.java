/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.invitation;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;
import pro.olli.config.AppConfig;
import pro.olli.models.BusinessUser;
import pro.olli.models.Invitation;
import pro.olli.repository.InvitationRepository;
import pro.olli.repository.UserRepository;

/**
 * Controller handling team invitation operations.
 */
@ApplicationScoped
@Path("/invitations")
public class InvitationController {

    private static final Logger LOG = Logger.getLogger(InvitationController.class);

    private final AppConfig config;
    private final InvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final ReactiveMailer mailer;

    @Inject
    public InvitationController(
        AppConfig config,
        InvitationRepository invitationRepository,
        UserRepository userRepository,
        ReactiveMailer mailer) {
        this.config = config;
        this.invitationRepository = invitationRepository;
        this.userRepository = userRepository;
        this.mailer = mailer;
    }

    /**
     * Create a new team invitation.
     *
     * @param request The invitation request containing email, businessId, and role
     * @param securityContext The security context to get the authenticated user
     * @return A response with the invitation details
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"user"})
    public Uni<Response> createInvitation(InvitationRequest request, @Context SecurityContext securityContext) {
        String currentUserId = securityContext.getUserPrincipal().getName();
        LOG.infof("Creating invitation for email %s to business %s by user %s",
            request.email, request.businessId, currentUserId);

        // First check if the current user is an admin for the business
        return invitationRepository.isUserAdmin(currentUserId, request.businessId)
            .flatMap(isAdmin -> {
                if (!isAdmin) {
                    LOG.warnf("User %s is not an admin for business %s", currentUserId, request.businessId);
                    return Uni.createFrom().item(Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "Only business admins can send invitations"))
                        .build());
                }

                // Generate a unique invitation ID
                String invitationId = generateInvitationId();

                // Create the invitation
                Invitation invitation = new Invitation(
                    invitationId,
                    request.businessId,
                    request.email,
                    request.role,
                    currentUserId);

                LOG.infof("Created invitation with ID %s", invitationId);

                // Save the invitation
                return invitationRepository.saveInvitation(invitation)
                    .flatMap(v -> {
                        LOG.infof("Saved invitation with ID %s", invitationId);
                        // Send email with invitation link
                        String invitationLink = String.format("%s/accept-invitation?id=%s", config.getAppDomain(),
                            invitationId);

                        return userRepository.getUser(currentUserId)
                            .flatMap(userOpt -> {
                                String senderName = userOpt.map(user ->
                                        user.getName() != null ? user.getName() : user.getEmail())
                                    .orElse("A team member");

                                Mail mail = Mail.withText(request.email,
                                    "You've Been Invited to Join Olli-AI",
                                    String.format(
                                        "%s has invited you to join their team on Olli-AI.\n\n" +
                                            "Click here to accept the invitation: %s\n\n" +
                                            "This invitation will expire in 7 days.",
                                        senderName, invitationLink
                                    )
                                );

                                // Send the email
                                return mailer.send(mail)
                                    .map(ignored -> {
                                        LOG.infof("Sent invitation email to %s", request.email);
                                        return Response.ok(Map.of(
                                            "id", invitationId,
                                            "email", request.email,
                                            "businessId", request.businessId,
                                            "role", request.role,
                                            "sentBy", currentUserId,
                                            "message", "Invitation sent successfully"
                                        )).build();
                                    });
                            });
                    })
                    .onFailure().recoverWithItem(e -> {
                        LOG.errorf("Failed to create invitation: %s", e.getMessage());
                        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(Map.of("error", "Failed to create invitation: " + e.getMessage()))
                            .build();
                    });
            });
    }

    /**
     * Get all invitations for the authenticated user's business.
     *
     * @param businessId The business ID
     * @param securityContext The security context to get the authenticated user
     * @return A response with the list of invitations
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"user"})
    public Uni<Response> getInvitations(@QueryParam("businessId") String businessId,
        @Context SecurityContext securityContext) {
        String currentUserId = securityContext.getUserPrincipal().getName();
        LOG.infof("Getting invitations for business %s by user %s", businessId, currentUserId);

        if (businessId == null || businessId.isBlank()) {
            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "Business ID is required"))
                .build());
        }

        // Check if the user is admin for the business
        return invitationRepository.isUserAdmin(currentUserId, businessId)
            .flatMap(isAdmin -> {
                if (!isAdmin) {
                    LOG.warnf("User %s is not an admin for business %s", currentUserId, businessId);
                    return Uni.createFrom().item(Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "Only business admins can view invitations"))
                        .build());
                }

                // Get all invitations for the business
                return invitationRepository.getInvitationsByBusiness(businessId)
                    .map(invitations -> {
                        LOG.infof("Found %d invitations for business %s", invitations.size(), businessId);
                        return Response.ok(invitations).build();
                    })
                    .onFailure().recoverWithItem(e -> {
                        LOG.errorf("Failed to get invitations: %s", e.getMessage());
                        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(Map.of("error", "Failed to get invitations: " + e.getMessage()))
                            .build();
                    });
            });
    }

    /**
     * Get all pending invitations for the authenticated user's business.
     *
     * @param businessId The business ID
     * @param securityContext The security context to get the authenticated user
     * @return A response with the list of pending invitations
     */
    @GET
    @Path("/pending")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"user"})
    public Uni<Response> getPendingInvitations(@QueryParam("businessId") String businessId,
        @Context SecurityContext securityContext) {
        String currentUserId = securityContext.getUserPrincipal().getName();
        LOG.infof("Getting pending invitations for business %s by user %s", businessId, currentUserId);

        if (businessId == null || businessId.isBlank()) {
            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "Business ID is required"))
                .build());
        }

        // Check if the user is admin for the business
        return invitationRepository.isUserAdmin(currentUserId, businessId)
            .flatMap(isAdmin -> {
                if (!isAdmin) {
                    LOG.warnf("User %s is not an admin for business %s", currentUserId, businessId);
                    return Uni.createFrom().item(Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "Only business admins can view pending invitations"))
                        .build());
                }

                // Get pending invitations
                return invitationRepository.getPendingInvitations(businessId)
                    .map(invitations -> {
                        LOG.infof("Found %d pending invitations for business %s", invitations.size(), businessId);
                        return Response.ok(invitations).build();
                    })
                    .onFailure().recoverWithItem(e -> {
                        LOG.errorf("Failed to get pending invitations: %s", e.getMessage());
                        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(Map.of("error", "Failed to get pending invitations: " + e.getMessage()))
                            .build();
                    });
            });
    }

    /**
     * Get all invitations sent to a specific email address. Doesn't require authentication - used for checking if a
     * user has pending invitations.
     *
     * @param email The email address
     * @return A response with the list of invitations for the email
     */
    @GET
    @Path("/check")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> checkInvitations(@QueryParam("email") String email) {
        LOG.infof("Checking invitations for email %s", email);

        if (email == null || email.isBlank()) {
            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "Email is required"))
                .build());
        }

        // Get invitations for the email that are not accepted and not expired
        return invitationRepository.getInvitationsByEmail(email)
            .map(invitations -> {
                // Filter to only include pending (not accepted and not expired) invitations
                List<Invitation> pendingInvitations = invitations.stream()
                    .filter(invitation -> !invitation.isAccepted() && !invitation.isExpired())
                    .toList();

                LOG.infof("Found %d pending invitations for email %s", pendingInvitations.size(), email);

                // Return minimal information for security
                return Response.ok(pendingInvitations.stream()
                    .map(invitation -> Map.of(
                        "id", invitation.getId(),
                        "businessId", invitation.getBusinessId(),
                        "role", invitation.getRole(),
                        "expiresAt", invitation.getExpiresAt().toEpochMilli()
                    ))
                    .toList()).build();
            })
            .onFailure().recoverWithItem(e -> {
                LOG.errorf("Failed to check invitations: %s", e.getMessage());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to check invitations: " + e.getMessage()))
                    .build();
            });
    }

    /**
     * Get a specific invitation by ID.
     *
     * @param id The invitation ID
     * @param securityContext The security context to get the authenticated user
     * @return A response with the invitation details if found
     */
    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"user"})
    public Uni<Response> getInvitation(@PathParam("id") String id, @Context SecurityContext securityContext) {
        String currentUserId = securityContext.getUserPrincipal().getName();
        LOG.infof("Getting invitation %s by user %s", id, currentUserId);

        return invitationRepository.getInvitation(id)
            .flatMap(invitationOpt -> {
                if (invitationOpt.isEmpty()) {
                    LOG.warnf("Invitation %s not found", id);
                    return Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Invitation not found"))
                        .build());
                }

                Invitation invitation = invitationOpt.get();

                // Verify the user is the sender or admin of the business
                return invitationRepository.isUserAdmin(currentUserId, invitation.getBusinessId())
                    .map(isAdmin -> {
                        boolean isSender = currentUserId.equals(invitation.getSentByUserId());
                        if (!isAdmin && !isSender) {
                            LOG.warnf("User %s is not authorized to view invitation %s", currentUserId, id);
                            return Response.status(Response.Status.FORBIDDEN)
                                .entity(Map.of("error", "You are not authorized to view this invitation"))
                                .build();
                        }

                        LOG.infof("Retrieved invitation %s", id);
                        return Response.ok(invitation).build();
                    });
            })
            .onFailure().recoverWithItem(e -> {
                LOG.errorf("Failed to get invitation: %s", e.getMessage());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to get invitation: " + e.getMessage()))
                    .build();
            });
    }

    /**
     * Accept an invitation to join a business.
     *
     * @param id The invitation ID
     * @param securityContext The security context to get the authenticated user
     * @return A response indicating success or failure
     */
    @POST
    @Path("/{id}/accept")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"user"})
    public Uni<Response> acceptInvitation(@PathParam("id") String id, @Context SecurityContext securityContext) {
        String currentUserId = securityContext.getUserPrincipal().getName();
        LOG.infof("Accepting invitation %s by user %s", id, currentUserId);

        return invitationRepository.getInvitation(id)
            .flatMap(invitationOpt -> {
                if (invitationOpt.isEmpty()) {
                    LOG.warnf("Invitation %s not found", id);
                    return Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Invitation not found"))
                        .build());
                }

                Invitation invitation = invitationOpt.get();

                // Check if invitation is for the current user
                return userRepository.getUser(currentUserId)
                    .flatMap(userOpt -> {
                        if (userOpt.isEmpty()) {
                            return Uni.createFrom().item(Response.status(Response.Status.UNAUTHORIZED)
                                .entity(Map.of("error", "User not found"))
                                .build());
                        }

                        String userEmail = userOpt.get().getEmail();
                        if (!userEmail.equals(invitation.getEmail())) {
                            LOG.warnf("User %s (%s) is not the invited user (%s)",
                                currentUserId, userEmail, invitation.getEmail());
                            return Uni.createFrom().item(Response.status(Response.Status.FORBIDDEN)
                                .entity(Map.of("error", "This invitation is not for your email address"))
                                .build());
                        }

                        // Check if invitation is expired
                        if (invitation.isExpired()) {
                            LOG.warnf("Invitation %s for user %s is expired", id, currentUserId);
                            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST)
                                .entity(Map.of("error", "This invitation has expired"))
                                .build());
                        }

                        // Check if invitation is already accepted
                        if (invitation.isAccepted()) {
                            LOG.infof("Invitation %s for user %s is already accepted", id, currentUserId);
                            return Uni.createFrom().item(Response.ok(Map.of(
                                "message", "This invitation has already been accepted",
                                "businessId", invitation.getBusinessId(),
                                "role", invitation.getRole()
                            )).build());
                        }

                        // Create a business user for the invited user
                        BusinessUser businessUser = new BusinessUser(
                            currentUserId,
                            invitation.getBusinessId(),
                            userEmail,
                            userOpt.get().getName(),
                            invitation.getRole() // Role from invitation
                        );

                        // Mark invitation as accepted
                        Invitation acceptedInvitation = invitation.withAccepted();

                        // Save both the business user and updated invitation
                        return userRepository.saveBusinessUser(businessUser)
                            .flatMap(v -> invitationRepository.saveInvitation(acceptedInvitation))
                            .map(v -> {
                                LOG.infof("User %s accepted invitation %s and joined business %s as %s",
                                    currentUserId, id, invitation.getBusinessId(), invitation.getRole());
                                return Response.ok(Map.of(
                                    "message", "Invitation accepted successfully",
                                    "businessId", invitation.getBusinessId(),
                                    "role", invitation.getRole()
                                )).build();
                            });
                    });
            })
            .onFailure().recoverWithItem(e -> {
                LOG.errorf("Failed to accept invitation: %s", e.getMessage());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to accept invitation: " + e.getMessage()))
                    .build();
            });
    }

    /**
     * Resend an invitation email.
     *
     * @param id The invitation ID
     * @param securityContext The security context to get the authenticated user
     * @return A response indicating success or failure
     */
    @POST
    @Path("/{id}/resend")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"user"})
    public Uni<Response> resendInvitation(@PathParam("id") String id, @Context SecurityContext securityContext) {
        String currentUserId = securityContext.getUserPrincipal().getName();
        LOG.infof("Resending invitation %s by user %s", id, currentUserId);

        return invitationRepository.getInvitation(id)
            .flatMap(invitationOpt -> {
                if (invitationOpt.isEmpty()) {
                    LOG.warnf("Invitation %s not found", id);
                    return Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Invitation not found"))
                        .build());
                }

                Invitation invitation = invitationOpt.get();

                // Check if the user is authorized to resend
                return invitationRepository.isUserAdmin(currentUserId, invitation.getBusinessId())
                    .flatMap(isAdmin -> {
                        boolean isSender = currentUserId.equals(invitation.getSentByUserId());
                        if (!isAdmin && !isSender) {
                            LOG.warnf("User %s is not authorized to resend invitation %s", currentUserId, id);
                            return Uni.createFrom().item(Response.status(Response.Status.FORBIDDEN)
                                .entity(Map.of("error", "You are not authorized to resend this invitation"))
                                .build());
                        }

                        // Check if invitation is already accepted
                        if (invitation.isAccepted()) {
                            LOG.infof("Cannot resend invitation %s because it is already accepted", id);
                            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST)
                                .entity(Map.of("error", "This invitation has already been accepted"))
                                .build());
                        }

                        // Check if invitation is expired
                        if (invitation.isExpired()) {
                            LOG.infof("Cannot resend invitation %s because it is expired", id);
                            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST)
                                .entity(Map.of("error", "This invitation has expired"))
                                .build());
                        }

                        // Send invitation email
                        String invitationLink = String.format("%s/accept-invitation?id=%s", config.getAppDomain(), id);

                        return userRepository.getUser(currentUserId)
                            .flatMap(userOpt -> {
                                String senderName = userOpt.map(user ->
                                        user.getName() != null ? user.getName() : user.getEmail())
                                    .orElse("A team member");

                                Mail mail = Mail.withText(invitation.getEmail(),
                                    "Reminder: You've Been Invited to Join Olli-AI",
                                    String.format(
                                        "%s has invited you to join their team on Olli-AI.\n\n" +
                                            "Click here to accept the invitation: %s\n\n" +
                                            "This invitation will expire on %s.",
                                        senderName, invitationLink, invitation.getExpiresAt().toString()
                                    )
                                );

                                // Send the email
                                return mailer.send(mail)
                                    .map(ignored -> {
                                        LOG.infof("Resent invitation email to %s", invitation.getEmail());
                                        return Response.ok(Map.of(
                                            "message", "Invitation resent successfully",
                                            "email", invitation.getEmail()
                                        )).build();
                                    });
                            });
                    });
            })
            .onFailure().recoverWithItem(e -> {
                LOG.errorf("Failed to resend invitation: %s", e.getMessage());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to resend invitation: " + e.getMessage()))
                    .build();
            });
    }

    /**
     * Cancel (delete) an invitation.
     *
     * @param id The invitation ID
     * @param securityContext The security context to get the authenticated user
     * @return A response indicating success or failure
     */
    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"user"})
    public Uni<Response> cancelInvitation(@PathParam("id") String id, @Context SecurityContext securityContext) {
        String currentUserId = securityContext.getUserPrincipal().getName();
        LOG.infof("Cancelling invitation %s by user %s", id, currentUserId);

        return invitationRepository.getInvitation(id)
            .flatMap(invitationOpt -> {
                if (invitationOpt.isEmpty()) {
                    LOG.warnf("Invitation %s not found", id);
                    return Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Invitation not found"))
                        .build());
                }

                Invitation invitation = invitationOpt.get();

                // Check if the user is authorized to cancel
                return invitationRepository.isUserAdmin(currentUserId, invitation.getBusinessId())
                    .flatMap(isAdmin -> {
                        boolean isSender = currentUserId.equals(invitation.getSentByUserId());
                        if (!isAdmin && !isSender) {
                            LOG.warnf("User %s is not authorized to cancel invitation %s", currentUserId, id);
                            return Uni.createFrom().item(Response.status(Response.Status.FORBIDDEN)
                                .entity(Map.of("error", "You are not authorized to cancel this invitation"))
                                .build());
                        }

                        // Delete the invitation
                        return invitationRepository.deleteInvitation(id)
                            .map(v -> {
                                LOG.infof("Cancelled invitation %s", id);
                                return Response.ok(Map.of(
                                    "message", "Invitation cancelled successfully"
                                )).build();
                            });
                    });
            })
            .onFailure().recoverWithItem(e -> {
                LOG.errorf("Failed to cancel invitation: %s", e.getMessage());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to cancel invitation: " + e.getMessage()))
                    .build();
            });
    }

    /**
     * Generate a unique invitation ID.
     *
     * @return A unique invitation ID
     */
    private String generateInvitationId() {
        return "inv-" + UUID.randomUUID().toString();
    }

    /**
     * Request object for creating invitations.
     */
    public static class InvitationRequest {

        public String email;
        public String businessId;
        public String role;
    }
}
